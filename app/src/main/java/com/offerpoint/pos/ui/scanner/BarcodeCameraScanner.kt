package com.offerpoint.pos.ui.scanner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Camera permission is requested only after the operator opens a scanning tool. */
@Composable
fun BarcodeCameraScanner(
    enabled: Boolean,
    onBarcode: (value: String, format: String) -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Long = 0L,
    receiptQrOnly: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context.findActivity()
    var permissionGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    val cameraPresent = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        permissionRequested = true
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionGranted = context.hasCameraPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        !cameraPresent -> CameraMessage("Camera unavailable", "This device has no camera. You can enter the barcode or receipt number manually.", modifier)
        !permissionGranted -> Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (receiptQrOnly) "Scan receipt QR" else "Scan product barcodes", style = MaterialTheme.typography.titleLarge)
                Text("Allow camera access to read a code. Images are processed on this device and are not saved.")
                if (permissionRequested) Text("Camera access is currently denied. You can allow it here or in Android app settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val showRationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showRationale) "Allow camera and try again" else "Allow camera")
                }
                if (permissionRequested && !showRationale) OutlinedButton(onClick = {
                    try {
                        settingsError = null
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                        settingsError = "Open this app's camera permission from your phone's Settings."
                    } catch (_: SecurityException) {
                        settingsError = "Android could not open settings. Allow the camera from your phone's Settings."
                    }
                }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Open camera settings") }
                if (settingsError != null) Text(settingsError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        else -> CameraPreview(enabled, onBarcode, modifier, resetKey, receiptQrOnly)
    }
}

@Composable
private fun CameraPreview(enabled: Boolean, onBarcode: (String, String) -> Unit, modifier: Modifier, resetKey: Long, receiptQrOnly: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val gate = remember { BarcodeScanGate() }
    val active = remember { AtomicBoolean(enabled) }
    val callback by rememberUpdatedState(onBarcode)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var torchError by remember { mutableStateOf<String?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var lastRead by remember { mutableStateOf<String?>(null) }
    active.set(enabled)
    LaunchedEffect(resetKey) { gate.rearm(); lastRead = null }
    DisposableEffect(owner, enabled) {
        if (!enabled) gate.pause()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) gate.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(camera, owner) {
        val info = camera?.cameraInfo
        val torchObserver = Observer<Int> { torchOn = it == TorchState.ON }
        val stateObserver = Observer<CameraState> { state ->
            val error = state.error
            if (error != null) cameraError = when (error.code) {
                CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                    "Another app is using the camera. Close it and try the camera again."
                CameraState.ERROR_CAMERA_DISABLED ->
                    "Android has disabled camera access. Check the phone's camera privacy setting."
                else -> "The camera stopped working. Try it again, or use Products to add an item."
            } else if (state.type == CameraState.Type.OPEN) cameraError = null
        }
        info?.torchState?.observe(owner, torchObserver)
        info?.cameraState?.observe(owner, stateObserver)
        onDispose {
            info?.torchState?.removeObserver(torchObserver)
            info?.cameraState?.removeObserver(stateObserver)
        }
    }

    DisposableEffect(previewView, owner, retry, receiptQrOnly) {
        cameraError = null
        val disposed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { work -> Thread(work, "pos-barcode-analyzer").apply { isDaemon = true } }
        val decoder = RetailBarcodeDecoder(receiptQrOnly)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(1280, 720))
            .build()
        var provider: ProcessCameraProvider? = null
        var lastAnalysis = 0L
        analysis.setAnalyzer(executor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (disposed.get() || !active.get() || now - lastAnalysis < 120L) return@setAnalyzer
                lastAnalysis = now
                val crop = image.cropRect
                val plane = image.planes.firstOrNull() ?: return@setAnalyzer
                val frame = copyLuminancePlane(plane.buffer, plane.rowStride, plane.pixelStride,
                    image.width, image.height, crop.left, crop.top, crop.width(), crop.height())
                    .rotateClockwise(image.imageInfo.rotationDegrees)
                val decoded = decoder.decode(frame)
                mainExecutor.execute {
                    if (disposed.get() || !active.get() || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@execute
                    if (decoded == null) gate.noBarcode(now)
                    else if (gate.accept(decoded.value, decoded.format, now)) {
                        lastRead = decoded.value
                        callback(decoded.value, decoded.format)
                    }
                }
            } catch (_: IllegalArgumentException) {
                // A malformed vendor frame must never wedge the camera or leak its ImageProxy.
            } catch (_: IllegalStateException) {
                // A camera can close between lifecycle transitions and delivery of its final frame.
            } finally {
                image.close()
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed.get()) return@addListener
            try {
                val ready = future.get()
                provider = ready
                val selector = when {
                    ready.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    ready.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> throw IllegalStateException("No usable camera is available.")
                }
                if (!disposed.get()) camera = ready.bindToLifecycle(owner, selector, preview, analysis)
            } catch (_: Exception) {
                if (!disposed.get()) cameraError = "The camera could not be opened. Close other camera apps and try again, or use Products."
            }
        }, mainExecutor)
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            try { camera?.cameraControl?.enableTorch(false) } catch (_: Exception) { /* Camera already closed. */ }
            camera = null
            // Only unbind this component's use cases; another camera consumer is not disturbed.
            try { provider?.unbind(preview, analysis) } catch (_: Exception) { /* Already disconnected by Android. */ }
            try { preview.setSurfaceProvider(null) } finally { executor.shutdown() }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (cameraError != null) {
            CameraMessage("Camera unavailable", cameraError!!, Modifier.fillMaxWidth())
            OutlinedButton(onClick = { retry += 1 }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Try camera again") }
        } else {
            Box(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 320.dp).clip(RoundedCornerShape(18.dp))) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize(), update = { view ->
                    view.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            val point = view.meteringPointFactory.createPoint(event.x, event.y)
                            try {
                                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
                            } catch (_: IllegalStateException) { /* Camera was paused during the tap. */ }
                            view.performClick()
                        }
                        true
                    }
                })
                Surface(Modifier.align(Alignment.Center).fillMaxWidth(0.9f).heightIn(min = 100.dp),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(10.dp)) {}
                Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape = RoundedCornerShape(10.dp)) {
                    Text(if (enabled) "Point at one barcode · Tap to focus" else "Scanning paused", Modifier.padding(10.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("Hold the barcode steady and keep it inside the camera view.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (camera?.cameraInfo?.hasFlashUnit() == true) OutlinedButton(onClick = {
                    torchError = null
                    camera?.cameraControl?.enableTorch(!torchOn)?.let { result ->
                        result.addListener({ try { result.get() } catch (_: Exception) { torchError = "The flashlight could not be changed." } }, mainExecutor)
                    }
                }, enabled = enabled) { Text(if (torchOn) "Flashlight off" else "Flashlight on") }
                if (lastRead != null) OutlinedButton(onClick = { gate.rearm(); lastRead = null }, enabled = enabled) { Text("Scan same item again") }
            }
            if (lastRead != null) Text("Read: $lastRead", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (torchError != null) Text(torchError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CameraMessage(title: String, body: String, modifier: Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun Context.hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.findActivity()
    else -> null
}
