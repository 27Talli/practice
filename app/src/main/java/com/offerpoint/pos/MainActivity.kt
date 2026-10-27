package com.offerpoint.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.offerpoint.pos.ui.navigation.OfferPointApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OfferPointApplication).container
        setContent {
            OfferPointApp(container.shopRepository, container.productRepository, container.inventoryRepository, container.salesRepository, container.barcodeLabelRepository, container.receiptSettingsRepository)
        }
    }
}
