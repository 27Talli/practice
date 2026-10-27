package com.offerpoint.pos.ui.scanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** One accepted frame fills a draft/lookup field. Opening this tool never posts a sale or return. */
@Composable
fun BarcodeCaptureDialog(title: String, description: String, onCaptured: (String, String) -> Unit, onDismiss: () -> Unit, receiptQrOnly: Boolean = false) {
    val owner = LocalLifecycleOwner.current
    var accepted by remember { mutableStateOf(false) }
    // Disarm immediately on cancel, including the frame before camera disposal/recomposition.
    val dismiss: () -> Unit = { accepted = true; onDismiss() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description)
                BarcodeCameraScanner(enabled = !accepted, receiptQrOnly = receiptQrOnly, onBarcode = { value, format ->
                    if (!accepted && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        accepted = true
                        onCaptured(value, format)
                    }
                })
                OutlinedButton(onClick = dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel scanning") }
            }
        }
    }
}
