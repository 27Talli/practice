package com.offerpoint.pos.data.backup

import android.database.Cursor
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.sqlite.db.SupportSQLiteDatabase
import com.offerpoint.pos.domain.model.BackupPreview
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Fixed topological order. No SQL identifiers ever come from an external backup. */
object DatabaseSnapshot {
    val tables = listOf("shops", "products", "product_barcodes", "shop_products", "inventory", "sales", "sale_items", "returns", "return_items",
        "stock_movements", "expenses", "audit_events", "app_settings", "sku_sequences", "sales_sequences", "return_sequences", "cost_code_settings", "product_labels", "receipt_settings")
    private val addedIn6=mapOf("shop_products" to setOf("reorderLevel"),"sale_items" to setOf("categorySnapshot"),
        "returns" to setOf("receiptNumber","requestId","requestFingerprint"),
        "expenses" to setOf("requestId","requestFingerprint","isVoided","voidReason","voidedAt"),
        "receipt_settings" to setOf("printerMode","printerKey","printerName","cutPaper"))
    private const val ROW_LIMIT = 2_000_000L

    data class Column(val name: String, val type: String, val primary: Int)
    fun columns(db: SupportSQLiteDatabase, table: String): List<Column> {
        require(table in tables)
        return db.query("PRAGMA table_info(`$table`)").use { c -> buildList { while (c.moveToNext()) add(Column(c.getString(1), c.getString(2), c.getInt(5))) } }
    }
    fun rows(db: SupportSQLiteDatabase, table: String): Cursor {
        val order = columns(db, table).filter { it.primary > 0 }.sortedBy { it.primary }.joinToString(",") { "`${it.name}`" }
        return db.query("SELECT * FROM `$table` ORDER BY $order")
    }

    fun write(db: SupportSQLiteDatabase, file: File, id: String, createdAt: Long, demo: Boolean) {
        JsonWriter(OutputStreamWriter(LimitedOutput(file.outputStream()), Charsets.UTF_8)).use { w ->
            w.beginObject().name("format").value(1).name("databaseVersion").value(6).name("backupId").value(id)
                .name("createdAt").value(createdAt).name("variant").value(if (demo) "DEMO" else "LIVE").name("tables").beginArray()
            var total = 0L
            for (table in tables) {
                w.beginObject().name("name").value(table).name("columns").beginArray()
                val cols = columns(db, table); cols.forEach { w.value(it.name) }; w.endArray().name("rows").beginArray()
                var count = 0L
                rows(db, table).use { c ->
                    while (c.moveToNext()) {
                        require(++total <= ROW_LIMIT) { "Backup has too many records." }; count++
                        w.beginArray()
                        for (i in cols.indices) when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> w.nullValue()
                            Cursor.FIELD_TYPE_INTEGER -> w.value(c.getLong(i))
                            Cursor.FIELD_TYPE_STRING -> { val text = c.getString(i); require(text.length <= 100_000) { "A database text field is too large for backup." }; w.value(text) }
                            else -> error("Unsupported database value in $table.")
                        }
                        w.endArray()
                    }
                }
                w.endArray().name("rowCount").value(count).endObject()
            }
            w.endArray().name("sequences").beginArray()
            db.query("SELECT name, seq FROM sqlite_sequence ORDER BY name").use { c -> while (c.moveToNext()) {
                require(c.getString(0) in tables)
                w.beginArray().value(c.getString(0)).value(c.getLong(1)).endArray()
            } }
            w.endArray().endObject()
        }
    }

    /** Caller owns a transaction in an EMPTY staging Room database. */
    fun read(db: SupportSQLiteDatabase, file: File, demo: Boolean, token: String): BackupPreview {
        require(file.length() <= BackupCipher.MAX_BYTES) { "Backup exceeds the size limit." }
        JsonReader(StringBoundedReader(file.reader(Charsets.UTF_8))).use { r ->
            r.isLenient = false
            fun name(expected: String) { require(r.nextName() == expected) { "Unsupported backup structure." } }
            fun integer(): Long { require(r.peek() == JsonToken.NUMBER); return r.nextString().toLongOrNull() ?: error("Invalid integer in backup.") }
            r.beginObject(); name("format"); require(integer() == 1L) { "Unsupported backup format version." }
            name("databaseVersion"); val version=integer();require(version in 5L..6L) { "Unsupported backup database version. Use a compatible app or the documented rescue tool." }
            name("backupId"); val id = r.nextString(); require(id.length == 36 && UUID.fromString(id).toString() == id) { "Invalid backup identity." }
            name("createdAt"); val timestamp = integer(); require(timestamp >= 0)
            name("variant"); require(r.nextString() == if (demo) "DEMO" else "LIVE") { "Live and Demo backups cannot be mixed." }
            name("tables"); r.beginArray()
            val counts = linkedMapOf<String, Long>(); var total = 0L
            val incomingTables=if(version==5L)tables.filter{it!="return_sequences"} else tables
            for (table in incomingTables) {
                r.beginObject(); name("name"); require(r.nextString() == table) { "Missing or unexpected backup table." }
                name("columns"); r.beginArray(); val cols = columns(db, table).filter { version!=5L || it.name !in addedIn6[table].orEmpty() }
                for (col in cols) require(r.nextString() == col.name) { "Unsupported backup columns." }
                r.endArray(); name("rows"); r.beginArray()
                val placeholders = cols.joinToString(",") { "?" }
                val statement = db.compileStatement("INSERT INTO `$table` (${cols.joinToString(",") { "`${it.name}`" }}) VALUES ($placeholders)")
                var count = 0L
                statement.use { insert ->
                    while (r.hasNext()) {
                        require(++total <= ROW_LIMIT) { "Backup has too many records." }; count++
                        r.beginArray(); insert.clearBindings()
                        for ((i, col) in cols.withIndex()) {
                            when {
                                r.peek() == JsonToken.NULL -> { r.nextNull(); insert.bindNull(i + 1) }
                                col.type == "INTEGER" -> insert.bindLong(i + 1, integer())
                                col.type == "TEXT" -> { require(r.peek() == JsonToken.STRING); val text = r.nextString(); require(text.length <= 100_000); insert.bindString(i + 1, text) }
                                else -> error("Unsupported backup column type.")
                            }
                        }
                        r.endArray(); insert.executeInsert()
                    }
                }
                r.endArray(); name("rowCount"); require(integer() == count) { "Backup record count does not match." }
                r.endObject(); counts[table] = count
            }
            r.endArray(); name("sequences"); r.beginArray()
            db.execSQL("DELETE FROM sqlite_sequence")
            val sequenceNames = mutableSetOf<String>()
            while (r.hasNext()) {
                r.beginArray(); val table = r.nextString(); val next = integer(); r.endArray()
                require(table in tables && columns(db, table).any { it.name == "id" && it.primary == 1 } && table != "shops" && table != "app_settings" && sequenceNames.add(table) && next >= 0)
                db.execSQL("INSERT INTO sqlite_sequence(name, seq) VALUES (?,?)", arrayOf(table, next))
                db.query("SELECT COALESCE(MAX(id),0) FROM `$table`").use { c -> c.moveToFirst(); require(next >= c.getLong(0)) { "Invalid backup ID counter." } }
            }
            r.endArray(); r.endObject(); require(r.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after backup." }
            for (table in tables.filter { t -> columns(db, t).any { it.name == "id" && it.primary == 1 } && t !in listOf("shops", "app_settings") }) {
                db.query("SELECT COUNT(*) FROM `$table`").use { c -> c.moveToFirst(); require(c.getLong(0) == 0L || table in sequenceNames) { "Missing backup ID counter." } }
            }
            val shops = db.query("SELECT name FROM shops ORDER BY id").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            for(table in tables)if(table !in counts)counts[table]=0
            return BackupPreview(token, id, timestamp, demo, counts, shops)
        }
    }

    fun fingerprint(db: SupportSQLiteDatabase): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun text(s: String) { val data = s.toByteArray(Charsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(data.size).array()); digest.update(data) }
        for (table in tables) {
            text(table)
            rows(db, table).use { c -> while (c.moveToNext()) { digest.update(42.toByte()); for (i in 0 until c.columnCount) {
                digest.update(c.getType(i).toByte())
                when (c.getType(i)) { Cursor.FIELD_TYPE_INTEGER -> digest.update(ByteBuffer.allocate(8).putLong(c.getLong(i)).array()); Cursor.FIELD_TYPE_STRING -> text(c.getString(i)); Cursor.FIELD_TYPE_NULL -> Unit; else -> error("Invalid database type.") }
            } } }
        }
        db.query("SELECT name,seq FROM sqlite_sequence ORDER BY name").use { c -> while(c.moveToNext()) { text(c.getString(0)); digest.update(ByteBuffer.allocate(8).putLong(c.getLong(1)).array()) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun replace(target: SupportSQLiteDatabase, source: SupportSQLiteDatabase) {
        // Keep higher already-issued IDs/references, even when restoring older business records.
        val skuCounters=target.query("SELECT prefix,nextValue FROM sku_sequences").use { c -> buildMap<String,Long> { while(c.moveToNext())put(c.getString(0),c.getLong(1)) } }
        val saleCounters=target.query("SELECT shopId,nextValue FROM sales_sequences").use { c -> buildMap<Long,Long> { while(c.moveToNext())put(c.getLong(0),c.getLong(1)) } }
        val returnCounters=target.query("SELECT shopId,nextValue FROM return_sequences").use { c -> buildMap<Long,Long> { while(c.moveToNext())put(c.getLong(0),c.getLong(1)) } }
        val rowCounters=target.query("SELECT name,seq FROM sqlite_sequence").use { c -> buildMap<String,Long> { while(c.moveToNext())put(c.getString(0),c.getLong(1)) } }
        for (table in tables.asReversed()) target.execSQL("DELETE FROM `$table`")
        for (table in tables) {
            val cols = columns(target, table)
            target.compileStatement("INSERT INTO `$table` VALUES (${cols.joinToString(",") { "?" }})").use { insert ->
                rows(source, table).use { c -> while (c.moveToNext()) {
                    insert.clearBindings()
                    for (i in cols.indices) when(c.getType(i)) { Cursor.FIELD_TYPE_NULL -> insert.bindNull(i+1); Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(i+1,c.getLong(i)); Cursor.FIELD_TYPE_STRING -> insert.bindString(i+1,c.getString(i)); else -> error("Invalid staged value.") }
                    insert.executeInsert()
                } }
            }
        }
        target.execSQL("DELETE FROM sqlite_sequence")
        source.query("SELECT name,seq FROM sqlite_sequence").use { c -> while(c.moveToNext()) target.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES (?,?)", arrayOf(c.getString(0),c.getLong(1))) }
        for((prefix,value) in skuCounters) {
            target.execSQL("INSERT OR IGNORE INTO sku_sequences(prefix,nextValue) VALUES (?,?)",arrayOf(prefix,value))
            target.execSQL("UPDATE sku_sequences SET nextValue=MAX(nextValue,?) WHERE prefix=?",arrayOf(value,prefix))
        }
        for((shopId,value) in saleCounters) {
            target.execSQL("INSERT OR IGNORE INTO sales_sequences(shopId,nextValue) VALUES (?,?)",arrayOf(shopId,value))
            target.execSQL("UPDATE sales_sequences SET nextValue=MAX(nextValue,?) WHERE shopId=?",arrayOf(value,shopId))
        }
        for((shopId,value) in returnCounters) {
            target.execSQL("INSERT OR IGNORE INTO return_sequences(shopId,nextValue) VALUES (?,?)",arrayOf(shopId,value))
            target.execSQL("UPDATE return_sequences SET nextValue=MAX(nextValue,?) WHERE shopId=?",arrayOf(value,shopId))
        }
        for((table,value) in rowCounters) {
            val restored=target.query("SELECT seq FROM sqlite_sequence WHERE name=?",arrayOf(table)).use { c -> if(c.moveToFirst()) c.getLong(0) else 0 }
            target.execSQL("DELETE FROM sqlite_sequence WHERE name=?",arrayOf(table))
            target.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES (?,?)",arrayOf(table,maxOf(value,restored)))
        }
    }

    private class LimitedOutput(output: OutputStream) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(b: Int) { require(++count <= BackupCipher.MAX_BYTES); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len; require(count <= BackupCipher.MAX_BYTES) { "Backup exceeds 128 MiB." }; out.write(b,off,len) }
    }
    private class StringBoundedReader(reader: Reader) : FilterReader(reader) {
        private var quoted = false; private var escaped = false; private var count = 0
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val n = super.read(buffer,offset,length)
            for (i in offset until offset + n.coerceAtLeast(0)) {
                val ch = buffer[i]
                if (quoted) { require(++count <= 600_002) { "Backup text field is too large." }; if (escaped) escaped=false else if (ch=='\\') escaped=true else if(ch=='"') quoted=false }
                else if(ch=='"') { quoted=true; count=0 }
            }
            return n
        }
    }
}
