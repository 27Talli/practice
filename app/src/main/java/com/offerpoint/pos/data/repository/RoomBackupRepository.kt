package com.offerpoint.pos.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import com.offerpoint.pos.data.backup.BackupCipher
import com.offerpoint.pos.data.backup.DatabaseSnapshot
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.local.database.withRestoreTransaction
import com.offerpoint.pos.data.local.entity.AuditEventEntity
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.BackupRepository
import com.offerpoint.pos.domain.usecase.BuildReceipt
import com.offerpoint.pos.domain.usecase.CostCode
import com.offerpoint.pos.domain.usecase.ReceiptValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** No raw database copying, WAL races, file replacement or destructive migration fallback. */
class RoomBackupRepository(context: Context, private val database: PosDatabase, private val demo: Boolean,
                           private val now: () -> Long = System::currentTimeMillis) : BackupRepository {
    private val context = context.applicationContext
    private val operations = Mutex()
    private val stages = ConcurrentHashMap<String, Stage>()
    private val directory = stagingDirectory(this.context)
    private data class Stage(val database: File, val sha256: String, val preview: BackupPreview, val source: String)
    private data class Exported(val file: File, val result: BackupResult, val fingerprint: String)
    override fun observeGeneration(): Flow<String> = database.posDao().observeDataGeneration()

    override suspend fun export(destination: String, password: CharArray): BackupResult = operations.withLock {
        withContext(Dispatchers.IO) {
            val exported = encryptedSnapshot(password)
            try { saveVerified(exported.file, destination); exported.result } finally { exported.file.delete() }
        }
    }

    override suspend fun inspect(source: String, password: CharArray): BackupPreview = operations.withLock {
        withContext(Dispatchers.IO) {
            val token = UUID.randomUUID().toString()
            val plain = File(directory, "$token.json")
            val stageFile = File(directory, "$token.db")
            var room: PosDatabase? = null
            try {
                requireNotNull(context.contentResolver.openInputStream(Uri.parse(source))) { "The backup could not be opened." }.use { input ->
                    plain.outputStream().use { out ->
                        try { BackupCipher.decrypt(input.buffered(), out.buffered(), password) }
                        catch (error: Exception) { throw IllegalArgumentException("The passphrase is incorrect, or the backup is damaged or unsupported.", error) }
                    }
                }
                val stageDb = Room.databaseBuilder(context, PosDatabase::class.java, stageFile.absolutePath).build().also { room = it }
                val preview = stageDb.withTransaction {
                    val result = DatabaseSnapshot.read(stageDb.openHelper.writableDatabase, plain, demo, token)
                    validate(stageDb); result
                }
                stageDb.close(); room = null
                stages[token] = Stage(stageFile, sha256(stageFile), preview, source)
                preview
            } catch (error: Exception) {
                room?.close(); deleteStage(stageFile); throw error
            } finally { plain.delete() }
        }
    }

    /** Caller explicitly confirms replacement. A readable external recovery file is mandatory. */
    override suspend fun restore(token: String, recoveryDestination: String, password: CharArray) = operations.withLock {
        withContext(Dispatchers.IO) {
            val stage = stages[token] ?: error("Inspect the backup again before restoring.")
            require(recoveryDestination != stage.source) { "Save the recovery backup to a new file, separate from the backup being restored." }
            require(sha256(stage.database) == stage.sha256) { "The inspected backup changed. Inspect it again." }
            val recovery = encryptedSnapshot(password)
            try {
                saveVerified(recovery.file, recoveryDestination)
                // Namespace changes may dispose the confirming ViewModel immediately after commit.
                // Finish source closure and token cleanup before honoring that cancellation.
                withContext(NonCancellable) {
                    require(sha256(stage.database) == stage.sha256) { "The inspected backup changed. Inspect it again." }
                    val source = Room.databaseBuilder(context, PosDatabase::class.java, stage.database.absolutePath).build()
                    try {
                        database.withRestoreTransaction {
                            val target = database.openHelper.writableDatabase
                            require(DatabaseSnapshot.fingerprint(target) == recovery.fingerprint) { "Shop data changed while saving the recovery backup. Nothing was replaced; inspect and try again." }
                            DatabaseSnapshot.replace(target, source.openHelper.readableDatabase)
                            val settings = requireNotNull(database.posDao().getSettings())
                            database.posDao().upsertSettings(settings.copy(dataGeneration = UUID.randomUUID().toString()))
                            database.posDao().insertAudit(AuditEventEntity(entityType = "DATABASE", entityId = 0, shopId = null,
                                action = "RESTORE_BACKUP", beforeValue = "{\"recoveryBackupId\":\"${recovery.result.backupId}\"}",
                                afterValue = "{\"backupId\":\"${stage.preview.backupId}\"}", createdAt = now()))
                            target.query("PRAGMA foreign_key_check").use { require(it.count == 0) { "Restored relationships are invalid." } }
                        }
                    } finally { source.close() }
                    discard(token)
                }
            } finally { recovery.file.delete() }
        }
    }

    override fun discard(token: String) { stages.remove(token)?.let { deleteStage(it.database) } }
    private fun deleteStage(file: File) { listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).forEach { it.delete() } }

    private suspend fun encryptedSnapshot(password: CharArray): Exported {
        BackupCipher.validatePassword(password)
        val id = UUID.randomUUID().toString(); val plain = File(directory, "$id.json"); val encrypted = File(directory, "$id.opbackup")
        try {
            val fingerprint = database.withTransaction {
                validate(database)
                DatabaseSnapshot.write(database.openHelper.readableDatabase, plain, id, now(), demo)
                DatabaseSnapshot.fingerprint(database.openHelper.readableDatabase)
            }
            plain.inputStream().buffered().use { input -> encrypted.outputStream().buffered().use { output -> BackupCipher.encrypt(input, output, password) } }
            return Exported(encrypted, BackupResult(id, encrypted.length(), sha256(encrypted)), fingerprint)
        } catch (error: Exception) { encrypted.delete(); throw error } finally { plain.delete() }
    }

    private fun saveVerified(file: File, destination: String) {
        val uri = Uri.parse(destination)
        require(uri.scheme == "content") { "Choose a destination using the Android file picker." }
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "The backup destination is not writable." }.use { output -> file.inputStream().use { it.copyTo(output) } }
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        requireNotNull(context.contentResolver.openInputStream(uri)) { "The saved recovery file cannot be verified. Choose another destination." }.use { input ->
            val bytes = ByteArray(65_536)
            while (true) { val n = input.read(bytes); if (n < 0) break; count += n; require(count <= file.length()) { "Saved backup verification failed." }; digest.update(bytes,0,n) }
        }
        require(count == file.length() && hex(digest.digest()) == sha256(file)) { "The saved backup could not be verified. Current shop data is unchanged." }
    }

    private suspend fun validate(room: PosDatabase) {
        val sql = room.openHelper.readableDatabase
        fun scalar(query: String): Long = sql.query(query).use { it.moveToFirst(); it.getLong(0) }
        sql.query("PRAGMA quick_check").use { require(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) { "Database integrity check failed." } }
        sql.query("PRAGMA foreign_key_check").use { require(it.count == 0) { "Backup has broken relationships." } }
        require(scalar("SELECT COUNT(*) FROM shops") == 2L && scalar("SELECT COUNT(*) FROM shops WHERE (id=1 AND code='OP') OR (id=2 AND code='FF')") == 2L) { "Backup must contain the two expected shops." }
        val settings = requireNotNull(room.posDao().getSettings()) { "Backup shop selection is missing." }
        require(scalar("SELECT COUNT(*) FROM app_settings") == 1L && settings.demoSeeded == demo) { "Live and Demo records cannot be mixed." }
        for ((table, where) in listOf("shop_products" to "purchaseCostPaise < 0 OR sellingPricePaise < 0", "inventory" to "quantity < 0", "expenses" to "amountPaise < 0",
            "sale_items" to "unitCostPaise < 0", "products" to "isActive NOT IN (0,1)", "app_settings" to "demoSeeded NOT IN (0,1)",
            "returns" to "refundPaise < 0", "return_items" to "quantity <= 0 OR restockQuantity < 0 OR restockQuantity > quantity OR unitRefundPaise < 0 OR refundPaise < 0",
            "sku_sequences" to "nextValue <= 0", "sales_sequences" to "nextValue <= 0", "sales" to "status != 'COMPLETED' OR completedAt IS NULL OR paymentMethod != 'CASH'")) {
            require(scalar("SELECT COUNT(*) FROM `$table` WHERE $where") == 0L) { "Invalid business values in $table." }
        }
        require(scalar("SELECT COUNT(*) FROM shop_products p LEFT JOIN inventory i ON i.shopId=p.shopId AND i.productId=p.productId WHERE i.productId IS NULL") == 0L) { "Inventory balance missing." }
        require(scalar("SELECT COUNT(*) FROM sale_items i WHERE (SELECT COUNT(*) FROM stock_movements m WHERE m.saleItemId=i.id AND m.type='SALE' AND m.delta=-i.quantity) != 1") == 0L) { "Sale stock history is incomplete." }
        sql.query("SELECT shopId,productId,quantity FROM inventory").use { inventory -> while(inventory.moveToNext()) {
            var quantity = 0L
            sql.query("SELECT delta,quantityAfter FROM stock_movements WHERE shopId=? AND productId=? ORDER BY id", arrayOf(inventory.getLong(0),inventory.getLong(1))).use { ledger -> while(ledger.moveToNext()) {
                quantity = Math.addExact(quantity,ledger.getLong(0)); require(quantity >= 0 && quantity == ledger.getLong(1)) { "Stock history is inconsistent." }
            } }
            require(quantity == inventory.getLong(2)) { "Inventory and stock history do not match." }
        } }
        val sales = RoomSalesRepository(room)
        sql.query("SELECT id,shopId FROM sales").use { cursor -> while(cursor.moveToNext()) {
            val id = cursor.getLong(0); val shopId = cursor.getLong(1)
            require(scalar("SELECT COUNT(*) FROM sale_items WHERE saleId=$id") in 1L..10_000L) { "Invalid sale item count." }
            val shop = requireNotNull(room.posDao().findShop(shopId))
            BuildReceipt.create(Shop(shop.id,shop.code,shop.name,shop.address),requireNotNull(sales.getSale(shopId,id)),ReceiptSettings(shopId),demo)
        } }
        sql.query("SELECT alphabet FROM cost_code_settings").use { c -> while(c.moveToNext()) CostCode.validateAlphabet(c.getString(0)) }
        sql.query("SELECT costCode,alphabetSnapshot,purchaseCostPaise FROM product_labels").use { c -> while(c.moveToNext()) require(CostCode.decode(c.getString(0),c.getString(1)) == c.getLong(2)) { "Invalid cost label snapshot." } }
        sql.query("SELECT shopId,paper,footer FROM receipt_settings").use { c -> while(c.moveToNext()) ReceiptValidation.settings(ReceiptSettings(c.getLong(0),ReceiptPaper.valueOf(c.getString(1)),c.getString(2))) }
    }

    private fun sha256(file: File): String { val digest=MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val bytes=ByteArray(65_536); while(true) { val n=input.read(bytes); if(n<0)break; digest.update(bytes,0,n) } }; return hex(digest.digest()) }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private companion object {
        val cleaned = AtomicBoolean(false)
        fun stagingDirectory(context: Context): File {
            if(cleaned.compareAndSet(false,true)) context.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("backup-staging-") }?.forEach { it.deleteRecursively() }
            return File(context.cacheDir,"backup-staging-${UUID.randomUUID()}").apply { check(mkdirs()) }
        }
    }
}
