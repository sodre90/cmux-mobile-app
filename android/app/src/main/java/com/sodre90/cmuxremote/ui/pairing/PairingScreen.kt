package com.sodre90.cmuxremote.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(vm: PairingViewModel, onPaired: () -> Unit) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(vm.state) {
        if (vm.state is PairingUiState.Success) onPaired()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Pair with cmux-bridge") }) }) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner),
            verticalArrangement = Arrangement.Top,
        ) {
            when (val state = vm.state) {
                is PairingUiState.Scanning -> if (hasCameraPermission) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CameraPreview(onQrDetected = vm::onQrScanned)
                    }
                } else {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Camera permission is required to scan the pairing QR code.")
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant camera permission")
                        }
                    }
                }
                is PairingUiState.Pairing -> Text("Pairing...", modifier = Modifier.padding(16.dp))
                is PairingUiState.Error -> Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.message)
                    Button(onClick = vm::retry) { Text("Scan again") }
                }
                is PairingUiState.Success -> Unit // LaunchedEffect above navigates away
            }
        }
    }
}

@Composable
private fun CameraPreview(onQrDetected: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
    }
    // Bound once resolved (async); unbound in onDispose below so the camera is
    // released as soon as this leaves composition (e.g. once pairing starts)
    // rather than staying held open for the lifetime of the whole Activity --
    // bindToLifecycle only reacts to lifecycleOwner's ON_STOP/ON_DESTROY, not
    // to this composable being removed from the tree.
    var boundCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose { boundCameraProvider?.unbindAll() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder().build().also { analysis ->
                        analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                            processImageProxy(scanner, imageProxy, onQrDetected)
                        }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                    boundCameraProvider = cameraProvider
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processImageProxy(scanner: BarcodeScanner, imageProxy: ImageProxy, onQrDetected: (String) -> Unit) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes -> barcodes.firstOrNull()?.rawValue?.let(onQrDetected) }
        .addOnCompleteListener { imageProxy.close() }
}
