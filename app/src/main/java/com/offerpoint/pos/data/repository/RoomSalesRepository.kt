package com.offerpoint.pos.data.repository

import androidx.room.withTransaction
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.local.entity.AuditEventEntity
import com.offerpoint.pos.data.local.entity.InventoryEntity
import com.offerpoint.pos.data.local.entity.ProductEntity
import com.offerpoint.pos.data.local.entity.SaleEntity
import com.offerpoint.pos.data.local.entity.SaleItemEntity
import com.offerpoint.pos.data.local.entity.SaleSequenceEntity
import com.offerpoint.pos.data.local.entity.ShopProductEntity
import com.offerpoint.pos.data.local.entity.StockMovementEntity
import com.offerpoint.pos.domain.model.CheckoutException
import com.offerpoint.pos.domain.model.CheckoutLine
import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.model.SaleDetails
import com.offerpoint.pos.domain.model.SaleSummary
import com.offerpoint.pos.domain.model.SoldItem
import com.offerpoint.pos.domain.model.TodaySales
import com.offerpoint.pos.domain.repository.SalesRepository
import com.offerpoint.pos.domain.usecase.BusinessDay
import com.offerpoint.pos.domain.usecase.CheckoutMath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

/** Sale, stock, receipt allocation and audit history either all commit or all roll back. */
class RoomSalesRepository(
    private val database: PosDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : SalesRepository {
    private val dao = database.posDao()

    override suspend fun completeSale(request: CheckoutRequest): SaleDetails {
        // Do not allow a caller's mutable list to change between validation and persistence.
        val stable = request.copy(lines = request.lines.toList())
        val totals = CheckoutMath.validate(stable)
        val fingerprint = CheckoutMath.fingerprint(stable)
        return database.withTransaction {
            val prior = dao.findSaleByRequest(stable.requestId)
            if (prior != null) {
                if (prior.shopId != stable.shopId || prior.requestFingerprint != fingerprint ||
                    prior.status != "COMPLETED" || prior.completedAt == null) {
                    throw CheckoutException("This checkout identifier was already used for a different sale. Check sales history before starting another sale.")
                }
                return@withTransaction details(prior)
            }

            val shop = dao.findShop(stable.shopId)
                ?: throw CheckoutException("The selected shop does not exist.")
            val validated = stable.lines.map { line -> validateLine(stable.shopId, line) }
            val timestamp = now()
            val receipt = nextReceipt(stable.shopId, shop.code)
            val saleId = dao.insertSale(SaleEntity(
                shopId = stable.shopId,
                receiptNumber = receipt,
                status = "COMPLETED",
                subtotalPaise = totals.subtotalPaise,
                discountPaise = totals.discountPaise,
                totalPaise = totals.totalPaise,
                amountPaidPaise = stable.amountPaidPaise,
                changePaise = CheckoutMath.change(totals.totalPaise, stable.amountPaidPaise),
                paymentMethod = "CASH",
                createdAt = timestamp,
                completedAt = timestamp,
                updatedAt = timestamp,
                checkoutRequestId = stable.requestId,
                requestFingerprint = fingerprint,
                orderDiscountPaise = stable.orderDiscountPaise,
            ))
            for (entry in validated) {
                val line = entry.line
                val itemId = dao.insertSaleItem(SaleItemEntity(
                    saleId = saleId,
                    shopId = stable.shopId,
                    productId = line.productId,
                    skuSnapshot = entry.product.sku,
                    nameSnapshot = entry.product.name,
                    variantSnapshot = entry.product.variant,
                    barcodeSnapshot = entry.barcode,
                    quantity = line.quantity,
                    unitPricePaise = line.unitPricePaise,
                    unitCostPaise = entry.association.purchaseCostPaise,
                    discountPaise = line.lineDiscountPaise,
                    lineTotalPaise = Math.subtractExact(Math.multiplyExact(line.quantity, line.unitPricePaise), line.lineDiscountPaise),
                    createdAt = timestamp,
                ))
                val quantityAfter = Math.subtractExact(entry.inventory.quantity, line.quantity)
                if (dao.updateInventoryQty(stable.shopId, line.productId, quantityAfter, timestamp) != 1) {
                    throw CheckoutException("Stock could not be updated. The sale has not been saved.")
                }
                dao.insertMovement(StockMovementEntity(
                    shopId = stable.shopId,
                    productId = line.productId,
                    delta = -line.quantity,
                    quantityAfter = quantityAfter,
                    type = "SALE",
                    reason = "Sale $receipt",
                    createdAt = timestamp,
                    saleItemId = itemId,
                ))
            }
            dao.insertAudit(AuditEventEntity(
                entityType = "SALE", entityId = saleId, shopId = stable.shopId,
                action = "COMPLETE_CASH_SALE", beforeValue = null,
                afterValue = "{\"saleId\":$saleId,\"totalPaise\":${totals.totalPaise},\"lineCount\":${stable.lines.size},\"paymentMethod\":\"CASH\"}",
                createdAt = timestamp,
            ))
            val saved = dao.getSale(stable.shopId, saleId)
                ?: throw CheckoutException("The completed sale could not be read back. The transaction was rolled back.")
            details(saved)
        }
    }

    override suspend fun getSale(shopId: Long, saleId: Long): SaleDetails? = database.withTransaction {
        dao.getSale(shopId, saleId)?.let { details(it) }
    }

    override suspend fun findCompletedRequest(shopId: Long, requestId: String): SaleDetails? = database.withTransaction {
        dao.findSaleByRequest(requestId)?.takeIf {
            it.shopId == shopId && it.status == "COMPLETED" && it.completedAt != null
        }?.let { details(it) }
    }

    override fun observeSales(shopId: Long): Flow<List<SaleSummary>> =
        dao.observeSales(shopId).map { sales -> sales.map { it.summary() } }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTodaySales(shopId: Long): Flow<TodaySales> = flow {
        while (currentCoroutineContext().isActive) {
            val timestamp = now()
            val bounds = BusinessDay.bounds(timestamp)
            emit(bounds)
            // Recheck the device clock at least once a minute, and exactly at local midnight.
            delay(minOf(60_000L, (bounds.second - timestamp).coerceAtLeast(1L)))
        }
    }.distinctUntilChanged().flatMapLatest { (start, end) ->
        dao.observeSalesInRange(shopId, start, end)
    }.map { sales ->
        val total = try {
            sales.fold(0L) { sum, sale -> Math.addExact(sum, sale.totalPaise) }
        } catch (_: ArithmeticException) {
            throw CheckoutException("Today's sales exceed the supported money range.")
        }
        TodaySales(total, sales.size)
    }

    private suspend fun validateLine(shopId: Long, line: CheckoutLine): ValidatedLine {
        val product = dao.findProduct(line.productId)
            ?: throw CheckoutException("A product in the cart no longer exists.")
        val association = dao.getAssociation(shopId, line.productId)
            ?: throw CheckoutException("${product.name} is not stocked by the selected shop.")
        if (!product.isActive || !association.isActive) {
            throw CheckoutException("${product.name} is inactive and cannot be sold.")
        }
        if (association.sellingPricePaise != line.unitPricePaise) {
            throw CheckoutException("The selling price for ${product.name} changed. Refresh cart prices and review the total before paying.")
        }
        if (association.purchaseCostPaise < 0 || association.sellingPricePaise < 0) {
            throw CheckoutException("${product.name} has an invalid price or purchase cost. Correct it in Products before checkout.")
        }
        val inventory = dao.getInventory(shopId, line.productId)
            ?: throw CheckoutException("${product.name} has no inventory balance for this shop.")
        if (inventory.quantity < line.quantity) {
            throw CheckoutException("Not enough stock for ${product.name}. Available: ${inventory.quantity}.")
        }
        val barcode = dao.barcodeForProduct(line.productId)?.value ?: product.sku
        return ValidatedLine(line, product, association, inventory, barcode)
    }

    private suspend fun nextReceipt(shopId: Long, code: String): String {
        val sequence = dao.getSaleSequence(shopId)
        var number = sequence?.nextValue ?: 1L
        var receipt: String
        do {
            if (number <= 0 || number == Long.MAX_VALUE) {
                throw CheckoutException("This shop's receipt sequence is exhausted.")
            }
            receipt = "$code-S-${number.toString().padStart(6, '0')}"
            number++
        } while (dao.receiptExists(shopId, receipt))
        val next = SaleSequenceEntity(shopId, number)
        if (sequence == null) dao.insertSaleSequence(next)
        else if (dao.updateSaleSequence(next) != 1) throw CheckoutException("Receipt numbering could not be updated.")
        return receipt
    }

    private suspend fun details(sale: SaleEntity): SaleDetails = SaleDetails(
        summary = sale.summary(),
        subtotalPaise = sale.subtotalPaise,
        discountPaise = sale.discountPaise,
        orderDiscountPaise = sale.orderDiscountPaise,
        amountPaidPaise = sale.amountPaidPaise,
        changePaise = sale.changePaise,
        paymentMethod = sale.paymentMethod,
        items = dao.getSaleItems(sale.shopId, sale.id).map { item ->
            SoldItem(item.productId, item.skuSnapshot, item.nameSnapshot, item.variantSnapshot,
                item.quantity, item.unitPricePaise, item.unitCostPaise, item.discountPaise, item.lineTotalPaise)
        },
    )

    private fun SaleEntity.summary(): SaleSummary = SaleSummary(
        id, shopId, receiptNumber, totalPaise,
        completedAt ?: throw CheckoutException("The saved sale is not completed."),
    )

    private data class ValidatedLine(
        val line: CheckoutLine,
        val product: ProductEntity,
        val association: ShopProductEntity,
        val inventory: InventoryEntity,
        val barcode: String,
    )
}
