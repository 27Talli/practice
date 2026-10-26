package com.offerpoint.pos.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add checkout metadata without rebuilding or deleting any Milestone 1 business table. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sales ADD COLUMN checkoutRequestId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE sales ADD COLUMN requestFingerprint TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE sales ADD COLUMN orderDiscountPaise INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sales_checkoutRequestId ON sales (checkoutRequestId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sales_sequences (
                shopId INTEGER NOT NULL,
                nextValue INTEGER NOT NULL,
                PRIMARY KEY(shopId),
                FOREIGN KEY(shopId) REFERENCES shops(id) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
    }
}
