package com.offerpoint.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.ReceiptSettingsRepository
import com.offerpoint.pos.domain.repository.SalesRepository
import com.offerpoint.pos.ui.receipts.ReceiptSettingsViewModel
import com.offerpoint.pos.ui.receipts.ReceiptViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ViewModel>()
    private val shop = Shop(1, "OP", "Offer Point", "Mardan")
    private val sales = FakeSales()
    private val preferences = FakePreferences()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun receipt() = ReceiptViewModel(sales, preferences, shop, 5, true).also(models::add)

    @Test fun previewAndReloadReadSavedSaleWithoutPostingAnotherSale() = runTest(dispatcher) {
        val vm = receipt(); runCurrent()
        assertEquals("OP-S-000005", vm.state.value.receipt?.receiptNumber)
        assertTrue(vm.state.value.receipt!!.demo)
        vm.load(); runCurrent()
        assertEquals(2, sales.reads)
        assertEquals(0, sales.posts)
    }

    @Test fun defaultPaperLoadsAndChangingPreviewDoesNotOverwriteShopPreferences() = runTest(dispatcher) {
        preferences.value = ReceiptSettings(1, ReceiptPaper.ROLL_58)
        val vm = receipt(); runCurrent()
        assertEquals(ReceiptPaper.ROLL_58, vm.state.value.paper)
        vm.paper(ReceiptPaper.ROLL_80)
        assertEquals(ReceiptPaper.ROLL_80, vm.state.value.paper)
        assertEquals(ReceiptPaper.ROLL_58, preferences.value.paper)
        assertEquals(0, preferences.writes)
    }

    @Test fun missingSaleShowsAnErrorAndCanBeRetried() = runTest(dispatcher) {
        sales.missing = true
        val vm = receipt(); runCurrent()
        assertNull(vm.state.value.receipt)
        assertNotNull(vm.state.value.error)
        sales.missing = false; vm.load(); runCurrent()
        assertNotNull(vm.state.value.receipt)
        assertNull(vm.state.value.error)
    }

    @Test fun wrongShopSaleCannotBecomeAReceipt() = runTest(dispatcher) {
        sales.wrongShop = true
        val vm = receipt(); runCurrent()
        assertNull(vm.state.value.receipt)
        assertNotNull(vm.state.value.error)
        assertEquals(0, sales.posts)
    }

    @Test fun settingsSaveIsValidatedAndRecoversAfterAnError() = runTest(dispatcher) {
        val vm = ReceiptSettingsViewModel(preferences, 1).also(models::add); runCurrent()
        vm.save(ReceiptPaper.ROLL_80, "X".repeat(241)); runCurrent()
        assertNotNull(vm.state.value.error)
        assertEquals(0, preferences.writes)
        preferences.failWrite = true; vm.save(ReceiptPaper.ROLL_80, "Welcome"); runCurrent()
        assertFalse(vm.state.value.saving)
        assertNotNull(vm.state.value.error)
        preferences.failWrite = false; vm.save(ReceiptPaper.ROLL_80, " Welcome "); runCurrent()
        assertEquals(ReceiptSettings(1, ReceiptPaper.ROLL_80, "Welcome"), vm.state.value.settings)
        assertNotNull(vm.state.value.notice)
        assertNull(vm.state.value.error)
    }

    private class FakePreferences : ReceiptSettingsRepository {
        var value = ReceiptSettings(1)
        var writes = 0
        var failWrite = false
        override suspend fun getSettings(shopId: Long) = value
        override suspend fun saveSettings(settings: ReceiptSettings) {
            if (failWrite) error("Disk failure")
            writes++; value = settings
        }
    }
    private class FakeSales : SalesRepository {
        var posts = 0; var reads = 0; var missing = false; var wrongShop = false
        override suspend fun getSale(shopId: Long, saleId: Long): SaleDetails? {
            reads++
            if (missing) return null
            return SaleDetails(SaleSummary(5, if (wrongShop) 2 else 1, "OP-S-000005", 500, 1),
                500, 0, 0, 500, 0, "CASH", listOf(SoldItem(1, "OP-1", "Lipstick", "Rose", 1, 500, 300, 0, 500)))
        }
        override suspend fun completeSale(request: CheckoutRequest): SaleDetails { posts++; error("Receipt screens must never post sales") }
        override suspend fun findCompletedRequest(shopId: Long, requestId: String): SaleDetails? = null
        override fun observeSales(shopId: Long) = flowOf(emptyList<SaleSummary>())
        override fun observeTodaySales(shopId: Long) = flowOf(TodaySales(0, 0))
    }
}
