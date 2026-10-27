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

/** Add immutable coded labels while keeping the global barcode registry and all business history. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS index_product_barcodes_productId_kind")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_product_barcodes_productId_kind ON product_barcodes (productId, kind)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_barcodes_id_productId ON product_barcodes (id, productId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cost_code_settings (
                shopId INTEGER NOT NULL,
                alphabet TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(shopId),
                FOREIGN KEY(shopId) REFERENCES shops(id) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS product_labels (
                barcodeId INTEGER NOT NULL,
                shopId INTEGER NOT NULL,
                productId INTEGER NOT NULL,
                skuSnapshot TEXT NOT NULL,
                nameSnapshot TEXT NOT NULL,
                variantSnapshot TEXT NOT NULL,
                purchaseCostPaise INTEGER NOT NULL,
                costCode TEXT NOT NULL,
                alphabetSnapshot TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(barcodeId),
                FOREIGN KEY(barcodeId, productId) REFERENCES product_barcodes(id, productId) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(shopId, productId) REFERENCES shop_products(shopId, productId) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_product_labels_barcodeId_productId ON product_labels (barcodeId, productId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_product_labels_shopId_productId ON product_labels (shopId, productId)")
    }
}
