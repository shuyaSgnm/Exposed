package org.jetbrains.exposed.v1.core

import org.jetbrains.exposed.v1.core.dao.id.CompositeID
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import org.jetbrains.exposed.v1.core.transactions.CoreTransactionManager
import org.jetbrains.exposed.v1.core.vendors.withDialect

/** A row of data representing a single record retrieved from a database result set. */
class ResultRow(
    /** Mapping of the expressions stored on this row to their index positions. */
    val fieldIndex: Map<Expression<*>, Int>,
    private val data: Array<Any?> = arrayOfNulls<Any?>(fieldIndex.size)
) {
    @OptIn(InternalApi::class)
    private val database: DatabaseApi? = CoreTransactionManager.currentTransactionOrNull()?.db

    private val lookUpCache = ResultRowCache()

    /**
     * Retrieves the value of a given expression on this row.
     *
     * @param expression expression to evaluate
     * @throws IllegalStateException if expression is not in record set or if result value is uninitialized
     *
     * @see [getOrNull] to get null in the cases an exception would be thrown
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(expression: Expression<T>): T {
        val column = expression as? Column<*>
        return when {
            column?.isEntityIdentifier() == true && column.table is CompositeIdTable -> {
                val resultID = CompositeID {
                    column.table.idColumns.forEach { column ->
                        it[column as Column<EntityID<Any>>] = getInternal(column, checkNullability = true).value
                    }
                }
                EntityID(resultID, column.table) as T
            }
            else -> getInternal(expression, checkNullability = true)
        }
    }

    /**
     * Sets the value of a given expression on this row.
     *
     * @param expression expression for which to set the value
     * @param value value to be set for the given expression
     */
    operator fun <T> set(expression: Expression<out T>, value: T) {
        setInternal(expression, value)
        lookUpCache.remove(expression)
    }

    private fun <T> setInternal(expression: Expression<out T>, value: T) {
        val index = getExpressionIndex(expression)
        data[index] = value
    }

    /** Whether the given [expression] has been initialized with a value on this row. */
    fun <T> hasValue(expression: Expression<T>): Boolean = fieldIndex[expression]?.let { data[it] != NotInitializedValue } ?: false

    /**
     * Retrieves the value of a given expression on this row.
     * Returns null in the cases an exception would be thrown in [get].
     *
     * @param expression expression to evaluate
     */
    fun <T> getOrNull(expression: Expression<T>): T? = if (hasValue(expression)) getInternal(expression, checkNullability = false) else null

    @OptIn(InternalApi::class)
    private fun <T> getInternal(expression: Expression<T>, checkNullability: Boolean): T = lookUpCache.cached(expression) {
        val rawValue = getRaw(expression)

        if (checkNullability) {
            if (rawValue == null && expression is Column<*> && expression.dbDefaultValue != null && !expression.columnType.nullable) {
                exposedLogger.warn(
                    "Column ${CoreTransactionManager.currentTransaction().fullIdentity(expression)} is marked as not null, " +
                        "has default db value, but returns null. Possible have to re-read it from DB."
                )
            }
        }

        database?.dialect?.let {
            withDialect(it) {
                rawToColumnValue(rawValue, expression)
            }
        } ?: rawToColumnValue(rawValue, expression)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> rawToColumnValue(raw: T?, expression: Expression<T>): T {
        return when {
            raw == null -> null
            raw == NotInitializedValue -> error("$expression is not initialized yet")
            expression is ExpressionWithColumnTypeAlias<T> -> rawToColumnValue(raw, expression.delegate)
            expression is ExpressionAlias<T> -> rawToColumnValue(raw, expression.delegate)
            expression is ExpressionWithColumnType<T> -> expression.columnType.valueFromDB(raw)
            expression is Op.OpBoolean -> BooleanColumnType.INSTANCE.valueFromDB(raw)
            else -> raw
        } as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getRaw(expression: Expression<T>): T? {
        if (expression is CompositeColumn<T>) {
            val rawParts = expression.getRealColumns().associateWith { getRaw(it) }
            return expression.restoreValueFromParts(rawParts)
        }

        val index = getExpressionIndex(expression)
        return data[index] as T?
    }

    /**
     * Retrieves the index of a given expression in the [fieldIndex] map.
     *
     * @param expression expression for which to get the index
     * @throws IllegalStateException if expression is not in record set
     */
    private fun <T> getExpressionIndex(expression: Expression<T>): Int {
        return fieldIndex[expression]
            ?: fieldIndex.keys.firstOrNull { exp ->
                when (exp) {
                    is Column<*> -> (exp.columnType as? EntityIDColumnType<*>)?.idColumn == expression
                    is IExpressionAlias<*> -> exp.delegate == expression
                    else -> false
                }
            }?.let { exp -> fieldIndex[exp] }
            ?: error("$expression is not in record set")
    }

    override fun toString(): String =
        fieldIndex.entries.joinToString { "${it.key}=${data[it.value]}" }

    internal object NotInitializedValue

    companion object {
        /** Creates a [ResultRow] storing all expressions in [fieldsIndex] with their values retrieved from a [RowApi]. */
        fun create(rs: RowApi, fieldsIndex: Map<Expression<*>, Int>): ResultRow {
            return ResultRow(fieldsIndex).apply {
                fieldsIndex.forEach { (field, index) ->
                    val columnType: IColumnType<out Any>? = (field as? ExpressionWithColumnType)?.columnType
                    val value = if (columnType != null) {
                        columnType.readObject(rs, index + 1)
                    } else {
                        rs.getObject(index + 1)
                    }
                    data[index] = value
                }
            }
        }

        /** Creates a [ResultRow] using the expressions and values provided by [data]. */
        fun createAndFillValues(data: Map<Expression<*>, Any?>): ResultRow {
            val fieldIndex = HashMap<Expression<*>, Int>(data.size)
            val values = arrayOfNulls<Any?>(data.size)
            data.entries.forEachIndexed { i, columnAndValue ->
                val (column, value) = columnAndValue
                fieldIndex[column] = i
                values[i] = value
            }
            return ResultRow(fieldIndex, values)
        }

        /** Creates a [ResultRow] storing [columns] with their default or nullable values. */
        fun createAndFillDefaults(columns: List<Column<*>>): ResultRow =
            ResultRow(columns.withIndex().associate { it.value to it.index }).apply {
                columns.forEach {
                    setInternal(it, it.defaultValueOrNotInitialized())
                }
            }
    }

    private fun <T> Column<T>.defaultValueOrNotInitialized(): Any? {
        return when {
            defaultValueFun != null -> when {
                columnType is ColumnWithTransform<*, *> -> {
                    (columnType as ColumnWithTransform<Any, Any>).unwrapRecursive(defaultValueFun!!())
                }
                else -> defaultValueFun!!()
            }
            columnType.nullable -> null
            else -> NotInitializedValue
        }
    }

    /**
     * [ResultRowCache] caches the values on reads by `expression`. The value cached by pair of `expression` itself and `columnType` of that expression.
     * It solves the problem of "equal" expression with different column type (like the same column with original type and [EntityIDColumnType])
     */
    private class ResultRowCache {
        private val values: MutableMap<Pair<Expression<*>, IColumnType<*>?>, Any?> = mutableMapOf()

        /**
         * Wrapping function that accept the expression and target function.
         * The function would be called if the value not found in the cache.
         *
         * @param expression is the key of caching
         * @param initializer function that returns the new value if the cache missed
         */
        fun <T> cached(expression: Expression<*>, initializer: () -> T): T = values.getOrPut(key(expression), initializer) as T

        /**
         * Remove the value by expression
         *
         * @param expression is the key of caching
         */
        fun remove(expression: Expression<*>) = values.remove(key(expression))

        private fun key(expression: Expression<*>): Pair<Expression<*>, IColumnType<*>?> = expression to (expression as? Column<*>)?.columnType
    }
}
