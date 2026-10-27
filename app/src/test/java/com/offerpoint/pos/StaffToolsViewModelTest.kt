package com.offerpoint.pos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.*
import com.offerpoint.pos.ui.staff.StaffToolsViewModel
import com.offerpoint.pos.ui.finance.RefundHistoryViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StaffToolsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ViewModel>()
    private val catalog = Products()
    private val labels = Labels()
    private val sales = Sales()
    private val refunds = Refunds()
    private val shop = Shop(1,"OP","Offer Point","Mardan")
    private val product = Product(7,1,"OP-000007","Lipstick","Cosmetics","Rose","000123",300,500,0)

    @Before fun before() { Dispatchers.setMain(dispatcher); catalog.product = product }
    @After fun after() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun staff() = StaffToolsViewModel(catalog,labels,1).also { models += it }
    private fun returns(saved: SavedStateHandle = SavedStateHandle()) = RefundHistoryViewModel(refunds,sales,shop,saved).also { models += it }
    private fun sale(cost: Long = 300) = SaleDetails(SaleSummary(81,1,"OP-S-000001",450,1),500,50,50,500,50,"CASH",
        listOf(SoldItem(7,"OP-000007","Sold lipstick","",1,500,cost,0,500)))

    @Test fun staffCanLookUpBothPricesWhenAnItemIsOutOfStockWithoutAnyWrite() = runTest(dispatcher) {
        val vm = staff(); vm.open(); vm.scan(true); vm.scanned("000123","CODE_128"); advanceUntilIdle()
        assertEquals(0L, vm.state.value.selected?.quantity)
        assertEquals(300L, vm.state.value.selected?.purchaseCostPaise)
        assertEquals(500L, vm.state.value.selected?.sellingPricePaise)
        assertFalse(vm.state.value.scanning)
    }
    @Test fun currentProductCostIsUsedInsteadOfAnOldCostLabelSnapshot() = runTest(dispatcher) {
        labels.row = InternalBarcodeLabel(1,1,7,product.sku,product.name,"","000123","OLD",123,"BLACKHORSE",1)
        val vm = staff(); vm.open(); vm.scan(true); vm.scanned("000123","CODE_128"); advanceUntilIdle()
        assertEquals(300L, vm.state.value.selected?.purchaseCostPaise)
    }
    @Test fun anotherShopsInternalLabelCannotRevealThatShopsCosts() = runTest(dispatcher) {
        labels.row = InternalBarcodeLabel(1,2,7,product.sku,product.name,"","000123","OLD",123,"BLACKHORSE",1)
        val vm = staff(); vm.open(); vm.scan(true); vm.scanned("000123","CODE_128"); advanceUntilIdle()
        assertNull(vm.state.value.selected); assertNotNull(vm.state.value.error)
    }
    @Test fun closingClearsPricesAndProfitAndIgnoresLateCameraFrames() = runTest(dispatcher) {
        val vm = staff(); vm.open(sale = sale()); advanceUntilIdle()
        assertEquals(150L, vm.state.value.profit?.profitPaise)
        vm.close(); vm.scanned("000123","CODE_128"); advanceUntilIdle()
        assertFalse(vm.state.value.open); assertNull(vm.state.value.profit); assertNull(vm.state.value.selected); assertTrue(vm.state.value.products.isEmpty())
    }
    @Test fun cartProfitUsesQuantityAndBothDiscountsButNeverPostsTheCart() = runTest(dispatcher) {
        val vm = staff(); vm.open(listOf(CheckoutLine(7,2,500,1)),"0.99"); advanceUntilIdle()
        assertEquals(300L,vm.state.value.profit?.profitPaise)
        assertFalse(vm.state.value.savedSale)
    }
    @Test fun aChangedSellingPriceRequiresCartReviewInsteadOfDisplayingMisleadingProfit() = runTest(dispatcher) {
        catalog.product = product.copy(sellingPricePaise = 600)
        val vm = staff(); vm.open(listOf(CheckoutLine(7,1,500)),"0"); advanceUntilIdle()
        assertNull(vm.state.value.profit); assertTrue(vm.state.value.profitError!!.contains("Refresh cart"))
    }
    @Test fun savedSaleProfitKeepsItsOriginalCostAfterTheProductCostChanges() = runTest(dispatcher) {
        catalog.product = product.copy(purchaseCostPaise = 999)
        val vm = staff(); vm.open(sale = sale()); advanceUntilIdle()
        assertEquals(150L,vm.state.value.profit?.profitPaise); assertTrue(vm.state.value.savedSale)
    }
    @Test fun malformedDiscountsDoNotFabricateAProfit() = runTest(dispatcher) {
        val vm = staff(); vm.open(listOf(CheckoutLine(7,1,500)),"-1"); advanceUntilIdle()
        assertNull(vm.state.value.profit); assertNotNull(vm.state.value.profitError)
    }
    @Test fun scanningAReceiptFindsAnExactSaleWithoutPostingAnyRefund() = runTest(dispatcher) {
        sales.row = sale()
        val vm = returns(); vm.scanned("OP-S-000001"); advanceUntilIdle()
        assertEquals(81L,vm.state.value.sale?.summary?.id)
        assertEquals(listOf(1L to "OP-S-000001"),sales.lookups)
        assertTrue(vm.state.value.rows.isEmpty())
    }
    @Test fun wrongShopAndRefundReceiptQrNeverReachTheSaleLookup() = runTest(dispatcher) {
        val vm = returns()
        for (reference in listOf("FF-S-000001","OP-R-000001","https://example.com")) {
            vm.scanned(reference); advanceUntilIdle(); assertNull(vm.state.value.sale); assertNotNull(vm.state.value.lookupError)
        }
        assertTrue(sales.lookups.isEmpty())
    }
    @Test fun missingReceiptAndRepositoryFailureLeaveNoRefundableSelection() = runTest(dispatcher) {
        val vm = returns(); vm.scanned("OP-S-000001"); advanceUntilIdle()
        assertNull(vm.state.value.sale); assertTrue(vm.state.value.lookupError!!.contains("No saved sale"))
        sales.failure = true; vm.find(); advanceUntilIdle()
        assertNull(vm.state.value.sale); assertNotNull(vm.state.value.lookupError)
    }
    @Test fun editingTheReceiptCancelsAnOldPendingLookup() = runTest(dispatcher) {
        sales.row = sale(); sales.gate = CompletableDeferred()
        val vm = returns(); vm.scanned("OP-S-000001"); runCurrent()
        vm.reference("OP-S-000002"); sales.gate!!.complete(Unit); advanceUntilIdle()
        assertNull(vm.state.value.sale); assertFalse(vm.state.value.lookingUp); assertEquals("OP-S-000002",vm.state.value.reference)
    }
    @Test fun restoredReceiptTextDoesNotAutomaticallySelectOrRefundASale() = runTest(dispatcher) {
        sales.row = sale(); val saved = SavedStateHandle(mapOf("receiptReference" to "OP-S-000001"))
        val vm = returns(saved); advanceUntilIdle()
        assertEquals("OP-S-000001",vm.state.value.reference); assertNull(vm.state.value.sale); assertTrue(sales.lookups.isEmpty())
    }

    private class Products : ProductRepository {
        var product: Product? = null
        override fun observeProducts(shopId:Long,query:String):Flow<List<Product>> = flowOf(listOfNotNull(product).filter { it.shopId == shopId })
        override suspend fun getProduct(shopId:Long,productId:Long) = product?.takeIf { it.shopId == shopId && it.id == productId }
        override suspend fun findByBarcode(barcode:String) = product?.takeIf { it.manufacturerBarcode == barcode }?.let { RegisteredProduct(it.id,it.sku,it.name,it.category,it.variant) }
        override suspend fun findProductForBarcode(shopId:Long,barcode:String) = product?.takeIf { it.shopId == shopId && it.manufacturerBarcode == barcode }
        override suspend fun createProduct(shopId:Long,draft:ProductDraft):Long = error("Unexpected write")
        override suspend fun updateProduct(shopId:Long,productId:Long,draft:ProductDraft):Unit = error("Unexpected write")
        override suspend fun associateProduct(shopId:Long,productId:Long,purchaseCostPaise:Long,sellingPricePaise:Long,openingQuantity:Long,reorderLevel:Long):Unit = error("Unexpected write")
    }
    private class Labels : BarcodeLabelRepository {
        var row:InternalBarcodeLabel? = null
        override suspend fun findLabelByValue(value:String) = row?.takeIf { it.barcodeValue == value }
        override suspend fun getLabel(shopId:Long,barcodeId:Long):InternalBarcodeLabel? = null
        override suspend fun getOrCreateCostCodeSettings(shopId:Long):CostCodeSettings = error("Unexpected write")
        override suspend fun updateCostCodeAlphabet(shopId:Long,alphabet:String):Unit = error("Unexpected write")
        override suspend fun createLabel(shopId:Long,productId:Long):InternalBarcodeLabel = error("Unexpected write")
    }
    private class Sales : SalesRepository {
        var row:SaleDetails? = null; var gate:CompletableDeferred<Unit>? = null; var failure = false
        val lookups = mutableListOf<Pair<Long,String>>()
        override suspend fun findSaleByReceipt(shopId:Long,receiptNumber:String):SaleDetails? { lookups += shopId to receiptNumber; gate?.await(); if(failure) error("Database unavailable"); return row?.takeIf { it.summary.shopId == shopId && it.summary.receiptNumber == receiptNumber } }
        override suspend fun completeSale(request:CheckoutRequest):SaleDetails = error("Unexpected sale")
        override suspend fun getSale(shopId:Long,saleId:Long) = row
        override suspend fun findCompletedRequest(shopId:Long,requestId:String):SaleDetails? = null
        override fun observeSales(shopId:Long):Flow<List<SaleSummary>> = flowOf(emptyList())
        override fun observeTodaySales(shopId:Long):Flow<TodaySales> = flowOf(TodaySales(0,0))
    }
    private class Refunds : RefundRepository {
        override fun observe(shopId:Long):Flow<List<RefundDetails>> = flowOf(emptyList())
        override suspend fun eligibility(shopId:Long,saleId:Long):ReturnEligibility = error("Unexpected eligibility")
        override suspend fun post(request:RefundRequest):RefundDetails = error("Unexpected refund")
        override suspend fun get(shopId:Long,id:Long):RefundDetails? = null
        override suspend fun findRequest(shopId:Long,requestId:String):RefundDetails? = null
    }
}
