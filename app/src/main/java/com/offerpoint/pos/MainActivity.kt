package com.offerpoint.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.offerpoint.pos.ui.navigation.OfferPointApp
import com.offerpoint.pos.ui.security.SecurityViewModel
import com.offerpoint.pos.ui.security.SecureWorkspace
import com.offerpoint.pos.ui.screens.PosViewModelFactory
import com.offerpoint.pos.ui.theme.OfferPointTheme
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private var security:SecurityViewModel?=null
    override fun onUserInteraction(){super.onUserInteraction();security?.touch()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OfferPointApplication).container
        val model=ViewModelProvider(this,PosViewModelFactory{SecurityViewModel(container.securityRepository,container.backupRepository)})[SecurityViewModel::class.java]
        security=model
        setContent {
            OfferPointTheme { SecureWorkspace(model) {
            OfferPointApp(container.shopRepository, container.productRepository, container.inventoryRepository, container.salesRepository, container.barcodeLabelRepository, container.receiptSettingsRepository, container.backupRepository, container.refundRepository, container.expenseRepository, container.reportRepository, container.securityRepository, model::lock)
            } }
        }
    }
}
