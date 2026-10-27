package com.offerpoint.pos.domain.usecase

import com.offerpoint.pos.domain.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object BuildRefundReceipt {
    fun create(shop:Shop,refund:RefundDetails,settings:ReceiptSettings,demo:Boolean):ReceiptDocument {
        require(shop.id==refund.shopId&&settings.shopId==shop.id&&refund.items.isNotEmpty()){"Choose a saved return from this shop."}
        require(refund.items.fold(0L){sum,item->require(item.quantity>0&&item.restockQuantity in 0..item.quantity&&item.refundPaise>=0);Math.addExact(sum,item.refundPaise)}==refund.refundPaise){"Saved refund totals are inconsistent."}
        val lines=buildList {
            add(ReceiptLine("Original sale: ${refund.originalReceiptNumber}"))
            refund.items.forEach{item->add(ReceiptLine(item.name,style=ReceiptLineStyle.EMPHASIS));add(ReceiptLine("${item.sku} ${item.variant}".trim()));add(ReceiptLine("Returned: ${item.quantity}",Money.format(item.refundPaise)));add(ReceiptLine("Restocked: ${item.restockQuantity}"))}
            add(ReceiptLine("CASH REFUND",Money.format(refund.refundPaise),ReceiptLineStyle.TOTAL));add(ReceiptLine("Reason: ${refund.reason}"))
            if(settings.footer.isNotBlank())add(ReceiptLine(settings.footer))
        }
        return ReceiptDocument(refund.id,shop.id,refund.receiptNumber,shop.name,shop.address,
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'PKT'",Locale.ENGLISH).withZone(ZoneId.of("Asia/Karachi")).format(Instant.ofEpochMilli(refund.createdAt)),demo,lines,"CASH REFUND RECEIPT")
    }
}
