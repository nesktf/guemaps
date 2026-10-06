package com.nesktf.guemaps.ui.share

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.nesktf.guemaps.R
import java.util.concurrent.Executors

@Composable
fun QrScannerScreen(
    onQrScanned: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onClose()
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var isTorchEnabled by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isScanned by remember { mutableStateOf(false) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val hints = mapOf(
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
                            DecodeHintType.CHARACTER_SET to "UTF-8"
                        )
                        val reader = MultiFormatReader().apply {
                            setHints(hints)
                        }

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            try {
                                if (!isScanned) {
                                    val resultText = processImageProxy(imageProxy, reader)
                                    if (resultText != null) {
                                        isScanned = true
                                        ContextCompat.getMainExecutor(ctx).execute {
                                            onQrScanned(resultText)
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                            } finally {
                                imageProxy.close()
                            }
                        }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            val boundCamera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                            camera = boundCamera

                            // Enable tap-to-focus
                            previewView.setOnTouchListener { view, event ->
                                if (event.action == android.view.MotionEvent.ACTION_UP) {
                                    val factory = previewView.meteringPointFactory
                                    val point = factory.createPoint(event.x, event.y)
                                    val action = FocusMeteringAction.Builder(point).build()
                                    boundCamera.cameraControl.startFocusAndMetering(action)
                                    view.performClick()
                                    true
                                } else {
                                    true
                                }
                            }
                        } catch (_: Exception) {}
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Viewfinder Overlay
            ScannerOverlay(modifier = Modifier.fillMaxSize())

            // Top Bar Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    IconButton(onClick = {
                        val newTorch = !isTorchEnabled
                        isTorchEnabled = newTorch
                        camera?.cameraControl?.enableTorch(newTorch)
                    }) {
                        Icon(
                            imageVector = if (isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = stringResource(R.string.scanner_toggle_flash),
                            tint = if (isTorchEnabled) Color.Yellow else Color.White
                        )
                    }
                }
            }

            // Bottom Instructions
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.scanner_instruction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        } else {
            // Permission Request Fallback
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.scanner_camera_permission_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.scanner_camera_permission_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.scanner_grant_permission))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onClose,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.action_back))
                }
            }
        }
    }
}

@Composable
private fun ScannerOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val scanSize = size.width * 0.72f
        val left = (size.width - scanSize) / 2f
        val top = (size.height - scanSize) / 2.2f
        val rect = Rect(left, top, left + scanSize, top + scanSize)

        val overlayPath = Path().apply {
            addRect(Rect(0f, 0f, size.width, size.height))
        }
        val cutoutPath = Path().apply {
            addRoundRect(RoundRect(rect, CornerRadius(24.dp.toPx(), 24.dp.toPx())))
        }

        clipPath(cutoutPath, clipOp = ClipOp.Difference) {
            drawRect(color = Color.Black.copy(alpha = 0.62f))
        }

        // Draw laser / corner guides
        val cornerLength = 32.dp.toPx()
        val strokeWidth = 4.dp.toPx()
        val cornerColor = Color(0xFF64B5F6)

        // Top Left
        drawLine(cornerColor, Offset(left, top + cornerLength), Offset(left, top + 16.dp.toPx()), strokeWidth)
        drawLine(cornerColor, Offset(left, top), Offset(left + cornerLength, top), strokeWidth)

        // Top Right
        drawLine(cornerColor, Offset(left + scanSize, top + cornerLength), Offset(left + scanSize, top + 16.dp.toPx()), strokeWidth)
        drawLine(cornerColor, Offset(left + scanSize - cornerLength, top), Offset(left + scanSize, top), strokeWidth)

        // Bottom Left
        drawLine(cornerColor, Offset(left, top + scanSize - cornerLength), Offset(left, top + scanSize - 16.dp.toPx()), strokeWidth)
        drawLine(cornerColor, Offset(left, top + scanSize), Offset(left + cornerLength, top + scanSize), strokeWidth)

        // Bottom Right
        drawLine(cornerColor, Offset(left + scanSize, top + scanSize - cornerLength), Offset(left + scanSize, top + scanSize - 16.dp.toPx()), strokeWidth)
        drawLine(cornerColor, Offset(left + scanSize - cornerLength, top + scanSize), Offset(left + scanSize, top + scanSize), strokeWidth)
    }
}

private fun extractLuminance(imageProxy: ImageProxy): Pair<ByteArray, Pair<Int, Int>> {
    val plane = imageProxy.planes[0]
    val buffer = plane.buffer
    val width = imageProxy.width
    val height = imageProxy.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride

    val yBytes = ByteArray(width * height)
    if (rowStride == width && pixelStride == 1) {
        buffer.rewind()
        buffer.get(yBytes, 0, width * height)
    } else {
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            if (pixelStride == 1) {
                buffer.get(yBytes, row * width, width)
            } else {
                for (col in 0 until width) {
                    yBytes[row * width + col] = buffer.get(row * rowStride + col * pixelStride)
                }
            }
        }
    }

    val rotation = imageProxy.imageInfo.rotationDegrees
    return when (rotation) {
        90 -> {
            val rotated = ByteArray(width * height)
            var idx = 0
            for (x in 0 until width) {
                for (y in height - 1 downTo 0) {
                    rotated[idx++] = yBytes[y * width + x]
                }
            }
            Pair(rotated, Pair(height, width))
        }
        180 -> {
            val rotated = ByteArray(width * height)
            var idx = 0
            for (x in width - 1 downTo 0) {
                for (y in height - 1 downTo 0) {
                    rotated[idx++] = yBytes[y * width + x]
                }
            }
            Pair(rotated, Pair(width, height))
        }
        270 -> {
            val rotated = ByteArray(width * height)
            var idx = 0
            for (x in width - 1 downTo 0) {
                for (y in 0 until height) {
                    rotated[idx++] = yBytes[y * width + x]
                }
            }
            Pair(rotated, Pair(height, width))
        }
        else -> Pair(yBytes, Pair(width, height))
    }
}

private fun processImageProxy(
    imageProxy: ImageProxy,
    reader: MultiFormatReader
): String? {
    return try {
        val (yData, dimensions) = extractLuminance(imageProxy)
        val (w, h) = dimensions

        val source = PlanarYUVLuminanceSource(
            yData,
            w,
            h,
            0,
            0,
            w,
            h,
            false
        )

        // 1. Try standard HybridBinarizer
        var result = runCatching {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        }.getOrNull()

        // 2. Try GlobalHistogramBinarizer
        if (result == null) {
            reader.reset()
            result = runCatching {
                reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source)))
            }.getOrNull()
        }

        // 3. Try Inverted Luminance Source (useful for dark themes / inverted contrast)
        if (result == null) {
            reader.reset()
            val inverted = source.invert()
            result = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(inverted)))
            }.getOrNull()
        }

        result?.text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
