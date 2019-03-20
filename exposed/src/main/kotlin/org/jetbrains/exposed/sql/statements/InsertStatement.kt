package org.jetbrains.exposed.sql.statements

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * isIgnore is supported for mysql only
 */
open class InsertStatement<Key:Any>(val table: Table, val isIgnore: Boolean = false) : UpdateBuilder<Int>(StatementType.INSERT, listOf(table)) {
    protected open val flushCache = true
    var resultedValues: List<ResultRow>? = null
        private set

    @Deprecated("Will be made internal on the next releases", level = DeprecationLevel.ERROR, replaceWith = ReplaceWith("get(autoIncColumn)"))
    open val generatedKey: Key? get() = autoIncColumns.firstOrNull()?.let { tryGet(it) } as Key?

    infix operator fun <T> get(column: Column<T>): T {
        val row = resultedValues?.firstOrNull() ?: error("No key generated")
        return row[column]
    }

    fun <T> tryGet(column: Column<T>): T? {
        val row = resultedValues?.firstOrNull()
        return row?.tryGet(column)
    }

    private fun processResults(rs: ResultSet?, inserted: Int): List<ResultRow> {
        val autoGeneratedKeys = arrayListOf<MutableMap<Column<*>, Any?>>()

        if (inserted > 0) {
            val firstAutoIncColumn = autoIncColumns.firstOrNull()
            if (firstAutoIncColumn != null) {
                val colIndx = try {
                    rs?.findColumn(firstAutoIncColumn.name)
                } catch (e: SQLException) {
                    null
                }
                while (rs?.next() == true) {
                    autoGeneratedKeys.add(hashMapOf(firstAutoIncColumn to rs.getObject(colIndx ?: 1)))
                }

                if (inserted > 1 && !currentDialect.supportsMultipleGeneratedKeys) {
                    // H2/SQLite only returns one last generated key...
                    (autoGeneratedKeys[0][firstAutoIncColumn] as? Number)?.toLong()?.let {
                        var id = it

                        while (autoGeneratedKeys.size < inserted) {
                            id -= 1
                            autoGeneratedKeys.add(0, hashMapOf(firstAutoIncColumn to id))
                        }
                    }
                }

                /** FIXME: https://github.com/JetBrains/Exposed/issues/129
                 *  doesn't work with MySQL `INSERT ... ON DUPLICATE UPDATE`
                 */
//            assert(isIgnore || autoGeneratedKeys.isEmpty() || autoGeneratedKeys.size == inserted) {
//                "Number of autoincs (${autoGeneratedKeys.size}) doesn't match number of batch entries ($inserted)"
//            }
            }
        }

        arguments!!.forEachIndexed { itemIndx, pairs ->
            val map = autoGeneratedKeys.getOrNull(itemIndx) ?: hashMapOf<Column<*>, Any?>().apply {
                autoGeneratedKeys.add(itemIndx, this)
            }
            pairs.forEach { (col, value) ->
                if (!col.columnType.isAutoInc && value != DefaultValueMarker) {
                    map[col] = value
                }
            }
        }
        return autoGeneratedKeys.map { data ->
            ResultRow.create(data.keys.toList()).also { r ->
                data.forEach { (c, v) -> r[c] = v }
            }
        }
    }

    protected open fun valuesAndDefaults(values: Map<Column<*>, Any?> = this.values): Map<Column<*>, Any?> {
        val columnsWithNotNullDefault = targets.flatMap { it.columns }.filter {
            (it.dbDefaultValue != null || it.defaultValueFun != null) && it !in values.keys
        }
        return values + columnsWithNotNullDefault.map { it to (it.defaultValueFun?.invoke() ?: DefaultValueMarker) }
    }

    override fun prepareSQL(transaction: Transaction): String {
        val builder = QueryBuilder(true)
        val values = arguments!!.first()
        val sql = if(values.isEmpty()) ""
        else values.joinToString(prefix = "VALUES (", postfix = ")") { (col, value) ->
            builder.registerArgument(col, value)
        }
        return transaction.db.dialect.functionProvider.insert(isIgnore, table, values.map { it.first }, sql, transaction)
    }

    protected open fun PreparedStatement.execInsertFunction() : Pair<Int, ResultSet?> {
        val inserted = if (arguments().count() > 1 || isAlwaysBatch) executeBatch().count() else executeUpdate()
        val rs = if (autoIncColumns.isNotEmpty()) { generatedKeys } else null
        return inserted to rs
    }

    override fun PreparedStatement.executeInternal(transaction: Transaction): Int {
        if (flushCache)
            transaction.flushCache()
        transaction.entityCache.removeTablesReferrers(listOf(table))
        val (inserted, rs) = execInsertFunction()
        return inserted.apply {
            resultedValues = processResults(rs, this)
        }
    }

    protected val autoIncColumns = targets.flatMap { it.columns }.filter { it.columnType.isAutoInc }

    override fun prepared(transaction: Transaction, sql: String): PreparedStatement = when {
        // https://github.com/pgjdbc/pgjdbc/issues/1168
        // Column names always escaped/quoted in RETURNING clause
        autoIncColumns.isNotEmpty() && currentDialect is PostgreSQLDialect ->
            transaction.connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)!!

        autoIncColumns.isNotEmpty() ->
            // http://viralpatel.net/blogs/oracle-java-jdbc-get-primary-key-insert-sql/
            transaction.connection.prepareStatement(sql, autoIncColumns.map { transaction.identity(it) }.toTypedArray())!!

        else ->
            transaction.connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)!!
    }

    protected open var arguments: List<List<Pair<Column<*>, Any?>>>? = null
        get() = field ?: run {
            val nullableColumns = table.columns.filter { it.columnType.nullable }
            val valuesAndDefaults = valuesAndDefaults()
            val result = (valuesAndDefaults + (nullableColumns - valuesAndDefaults.keys).associate { it to null }).toList().sortedBy { it.first }
            listOf(result).apply { field = this }
        }

    override fun arguments() = arguments!!.map { it.map { it.first.columnType to it.second }.filter { it.second != DefaultValueMarker} }
}
