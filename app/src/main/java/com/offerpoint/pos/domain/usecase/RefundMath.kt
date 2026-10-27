package com.offerpoint.pos.domain.usecase

import com.offerpoint.pos.domain.model.*
import java.math.BigInteger
import java.security.MessageDigest

/** Hamilton allocation across lines, then cumulative per-unit allocation: full returns conserve every paisa. */
object RefundMath {
    fun netLines(lineTotals: List<Long>, orderDiscount: Long): List<Long> {
        require(lineTotals.isNotEmpty() && lineTotals.all { it >= 0 } && orderDiscount >= 0) { "Invalid saved sale amounts." }
        val total=lineTotals.fold(0L,Math::addExact)
        require(orderDiscount<=total) { "Invalid saved order discount." }
        if(total==0L)return lineTotals
        val denominator=BigInteger.valueOf(total)
        val parts=lineTotals.map { BigInteger.valueOf(it).multiply(BigInteger.valueOf(orderDiscount)).divideAndRemainder(denominator) }
        val allocated=parts.map{it[0].longValueExact()}.toMutableList()
        var left=Math.subtractExact(orderDiscount,allocated.fold(0L,Math::addExact))
        for(index in parts.indices.sortedWith(compareByDescending<Int>{parts[it][1]}.thenBy{it})) {
            if(left==0L)break;allocated[index]=Math.addExact(allocated[index],1);left--
        }
        return lineTotals.indices.map { Math.subtractExact(lineTotals[it],allocated[it]) }
    }
    fun amount(netLine:Long,sold:Long,previous:Long,quantity:Long):Long {
        require(netLine>=0 && sold>0 && previous>=0 && quantity>0 && Math.addExact(previous,quantity)<=sold) { "Return quantity exceeds the remaining sold quantity." }
        fun cumulative(q:Long)=BigInteger.valueOf(netLine).multiply(BigInteger.valueOf(q)).divide(BigInteger.valueOf(sold)).longValueExact()
        return Math.subtractExact(cumulative(previous+quantity),cumulative(previous))
    }
    fun validate(request:RefundRequest):RefundRequest {
        require(request.shopId>0 && request.saleId>0 && request.requestId.isNotBlank() && request.requestId.length<=128) { "Choose a saved sale before returning items." }
        require(request.lines.isNotEmpty() && request.lines.map{it.saleItemId}.distinct().size==request.lines.size) { "Choose each returned item once." }
        require(request.lines.all { it.saleItemId>0 && it.quantity>0 && it.restockQuantity in 0..it.quantity }) { "Enter valid return and restock quantities." }
        require(request.expectedRefundPaise>=0) { "Invalid refund amount." }
        val reason=request.reason.trim();require(reason.length in 1..240) { "Enter a return reason of 1–240 characters." }
        return request.copy(reason=reason,lines=request.lines.toList())
    }
    fun fingerprint(request:RefundRequest):String = sha256(buildString {
        append(request.shopId).append('|').append(request.saleId).append('|').append(request.expectedRefundPaise).append('|').append(request.reason.length).append(':').append(request.reason)
        request.lines.sortedBy{it.saleItemId}.forEach{append(';').append(it.saleItemId).append(',').append(it.quantity).append(',').append(it.restockQuantity)}
    })
    fun sha256(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}
