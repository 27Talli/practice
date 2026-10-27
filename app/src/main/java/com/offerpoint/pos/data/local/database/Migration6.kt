package com.offerpoint.pos.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_5_6:Migration=object:Migration(5,6) {
    override fun migrate(db:SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE shop_products ADD COLUMN reorderLevel INTEGER NOT NULL DEFAULT 5")
        db.execSQL("ALTER TABLE sale_items ADD COLUMN categorySnapshot TEXT NOT NULL DEFAULT ''")
        for(column in listOf("receiptNumber","requestId","requestFingerprint"))db.execSQL("ALTER TABLE returns ADD COLUMN $column TEXT DEFAULT NULL")
        db.execSQL("CREATE UNIQUE INDEX index_returns_requestId ON returns(requestId)")
        db.execSQL("CREATE UNIQUE INDEX index_returns_shopId_receiptNumber ON returns(shopId,receiptNumber)")
        for(column in listOf("requestId","requestFingerprint"))db.execSQL("ALTER TABLE expenses ADD COLUMN $column TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE expenses ADD COLUMN isVoided INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE expenses ADD COLUMN voidReason TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE expenses ADD COLUMN voidedAt INTEGER DEFAULT NULL")
        db.execSQL("CREATE UNIQUE INDEX index_expenses_requestId ON expenses(requestId)")
        db.execSQL("ALTER TABLE receipt_settings ADD COLUMN printerMode TEXT NOT NULL DEFAULT 'ANDROID'")
        db.execSQL("ALTER TABLE receipt_settings ADD COLUMN printerKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE receipt_settings ADD COLUMN printerName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE receipt_settings ADD COLUMN cutPaper INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE return_sequences (shopId INTEGER NOT NULL, nextValue INTEGER NOT NULL, PRIMARY KEY(shopId), FOREIGN KEY(shopId) REFERENCES shops(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE TABLE owner_security (id INTEGER NOT NULL, salt TEXT, pinHash TEXT, failedAttempts INTEGER NOT NULL, lockedUntil INTEGER NOT NULL, autoLockMinutes INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
    }
}
