package com.offerpoint.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import com.offerpoint.pos.domain.model.AppAppearance
import com.offerpoint.pos.ui.navigation.OfferPointApp
import com.offerpoint.pos.ui.security.SecurityViewModel
import com.offerpoint.pos.ui.security.SecureWorkspace
import com.offerpoint.pos.ui.screens.PosViewModelFactory
import com.offerpoint.pos.ui.theme.OfferPointTheme
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offerpoint.pos.domain.model.WorkspacePreferencesSnapshot
import com.offerpoint.pos.ui.theme.LocalWorkspacePreferences

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
            val preferences by remember(container.workspacePreferencesRepository) { container.workspacePreferencesRepository.observe() }
                .collectAsStateWithLifecycle(initialValue = WorkspacePreferencesSnapshot())
            val dark = when (preferences.value.appearance) {
                AppAppearance.SYSTEM -> isSystemInDarkTheme()
                AppAppearance.LIGHT -> false
                AppAppearance.DARK -> true
            }
            SideEffect {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            CompositionLocalProvider(LocalWorkspacePreferences provides preferences.value) {
            OfferPointTheme { SecureWorkspace(model) {
            OfferPointApp(container.shopRepository, container.productRepository, container.inventoryRepository, container.salesRepository, container.barcodeLabelRepository, container.receiptSettingsRepository, container.backupRepository, container.refundRepository, container.expenseRepository, container.reportRepository, container.securityRepository, container.workspacePreferencesRepository, model::lock)
            } }
            }
        }
    }
}
