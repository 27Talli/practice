package com.offerpoint.pos.domain.usecase

import com.offerpoint.pos.domain.model.*
import java.io.Writer

object ReportCsv {
    private fun text(value:String):String {val safe=if(value.trimStart().firstOrNull() in listOf('=','+','-','@','\t','\r'))"'$value" else value;return "\"${safe.replace("\"","\"\"")}\""}
    fun write(writer:Writer,shop:Shop,report:FinancialReport) {
        fun row(a:String,b:String){writer.write(text(a)+","+b+"\r\n")}
        row("Shop",text(shop.name));row("Period start UTC milliseconds",report.start.toString());row("Period end exclusive UTC milliseconds",report.endExclusive.toString())
        row("Metric (PKR)","Amount")
        for((label,value) in listOf("Completed sales" to report.salesPaise,"Cash refunds" to report.refundsPaise,"Net sales" to report.netSalesPaise,
            "Recorded net cost of goods" to report.netCostPaise,"Gross profit" to report.grossProfitPaise,"Expenses" to report.expensesPaise,
            "Operating profit" to report.operatingProfitPaise,"Net operating cash movement" to report.cashMovementPaise,"Current inventory cost value" to report.currentStockCostPaise))row(label,Money.input(value))
        writer.write("\r\nSKU,Product,Net units,Net revenue PKR\r\n")
        report.products.forEach{writer.write("${text(it.sku)},${text(it.name)},${it.quantity},${Money.input(it.revenuePaise)}\r\n")}
        writer.write("\r\nCategory,Net revenue PKR\r\n");report.categories.forEach{row(it.category,Money.input(it.revenuePaise))};writer.flush()
    }
}
