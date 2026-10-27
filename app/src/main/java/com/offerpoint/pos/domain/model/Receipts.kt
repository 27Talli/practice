package com.offerpoint.pos.domain.model

enum class ReceiptPaper(val title: String) { A4("A4 sheet"), ROLL_58("58 mm receipt"), ROLL_80("80 mm receipt") }
enum class PrinterMode { ANDROID, BLUETOOTH, USB }

data class ReceiptSettings(
    val shopId: Long,
    val paper: ReceiptPaper = ReceiptPaper.A4,
    val footer: String = "Thank you for shopping with us.",
    val printerMode: PrinterMode = PrinterMode.ANDROID,
    val printerKey: String = "",
    val printerName: String = "",
    val cutPaper: Boolean = false,
)

enum class ReceiptLineStyle { NORMAL, EMPHASIS, TOTAL }
data class ReceiptLine(val text: String, val value: String? = null, val style: ReceiptLineStyle = ReceiptLineStyle.NORMAL)

/** Customer-safe projection: no purchase cost, private key or cost-label barcode fields. */
data class ReceiptDocument(
    val saleId: Long,
    val shopId: Long,
    val receiptNumber: String,
    val shopName: String,
    val shopAddress: String,
    val soldAt: String,
    val demo: Boolean,
    val lines: List<ReceiptLine>,
    val title: String = "SALE RECEIPT",
)
