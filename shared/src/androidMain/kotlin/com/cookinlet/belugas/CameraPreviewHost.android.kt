package com.cookinlet.belugas

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

@Composable
actual fun CameraPreviewHost(
    modifier: Modifier,
    takePhotoSignal: Boolean,
    onPhotoCaptured: (String) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }

    // Pixel size of this composable's own root Box, which fills exactly the same area as the
    // sibling SketchedReticle overlay in CaptureScreen -- lets the capture callback below work
    // out what fraction of the frame the reticle covers without needing to reach into a
    // different composable's layout.
    var hostSizePx by remember { mutableStateOf<IntSize?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasCameraPermission = isGranted
        }
    )

    // CameraX ImageCapture Use Case. No fixed target aspect ratio: this use case is bound
    // together with Preview inside a ViewPort-scoped UseCaseGroup below, which is what
    // actually determines the captured frame's crop/aspect (matching what's shown on screen)
    // -- forcing a separate ratio here would fight that and reintroduce the preview/capture
    // field-of-view mismatch the reticle crop below depends on not having.
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // Apply zoom ratio to camera hardware
    LaunchedEffect(zoomRatio) {
        camera?.cameraControl?.setZoomRatio(zoomRatio)
    }

    // Trigger Photo Snapshot when user taps "LOG POD"
    LaunchedEffect(takePhotoSignal) {
        if (takePhotoSignal) {
            val photoFile = File(
                context.cacheDir,
                "BELUGA_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            )

            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val hostSize = hostSizePx
                        if (hostSize != null) {
                            try {
                                cropToReticleSquare(photoFile, hostSize, density)
                            } catch (e: Exception) {
                                // Not fatal -- fall through and upload the uncropped frame
                                // rather than losing the sighting over a crop failure.
                                println("RETICLE_CROP_ERROR: [${e::class.simpleName}] ${e.message}")
                                e.printStackTrace()
                            }
                        }
                        // Pass the absolute file path to LoggingScreen
                        onPhotoCaptured(photoFile.absolutePath)
                    }

                    override fun onError(exc: ImageCaptureException) {
                        exc.printStackTrace()
                    }
                }
            )
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission required for viewfinder", color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Enable Camera")
                }
            }
        }
    } else {
        Box(
            modifier = modifier
                .onGloballyPositioned { coordinates -> hostSizePx = coordinates.size }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        zoomRatio = (zoomRatio * zoom).coerceIn(1f, 5f)
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { previewView ->
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder()
                            .build()
                            .also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            // Bind Preview and ImageCapture through a shared ViewPort so the
                            // captured photo's crop matches what's actually shown on screen --
                            // without this, ImageCapture's own aspect ratio can differ from the
                            // FILL_CENTER-scaled preview, and the reticle crop above would be
                            // cropping the wrong region. previewView.viewPort is only non-null
                            // once the view has been laid out at least once; fall back to a
                            // plain (unmatched) bind on the rare first-frame race rather than
                            // skip binding the camera entirely.
                            val viewPort = previewView.viewPort
                            camera = if (viewPort != null) {
                                val useCaseGroup = UseCaseGroup.Builder()
                                    .setViewPort(viewPort)
                                    .addUseCase(preview)
                                    .addUseCase(imageCapture)
                                    .build()
                                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
                            } else {
                                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            )

            // Zoom Slider Overlay (1x to 5x)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${"%.1f".format(zoomRatio)}x", color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                
                // Rotated Slider to act as a vertical control
                Slider(
                    value = zoomRatio,
                    onValueChange = { zoomRatio = it },
                    valueRange = 1f..5f,
                    modifier = Modifier
                        .height(150.dp)
                        .width(40.dp)
                        .graphicsLayer {
                            rotationZ = 270f
                        }
                )
            }
        }
    }
}

/**
 * Crops the just-captured JPEG down to a square region matching the on-screen reticle, then
 * overwrites [photoFile] with the result. [hostSize] is the pixel size of this composable's
 * own root Box (which the sibling SketchedReticle overlay in CaptureScreen shares exactly),
 * and [density] converts the reticle's fixed dp size into that same pixel space.
 *
 * The square's side is the reticle's height (its smaller dimension, so the crop stays fully
 * within the reticle's rectangular bounds) and is centered on the same point the reticle is
 * -- the screen/host center. This only produces the right region because Preview and
 * ImageCapture are bound through a shared ViewPort (see the binding above), which keeps the
 * saved photo's aspect ratio and framing matched to what's actually shown on screen.
 */
private fun cropToReticleSquare(photoFile: File, hostSize: IntSize, density: Density) {
    if (hostSize.width <= 0 || hostSize.height <= 0) return

    val reticleHeightPx = with(density) { RETICLE_HEIGHT_DP.dp.toPx() }
    val squareFractionOfHostHeight = (reticleHeightPx / hostSize.height).coerceIn(0f, 1f)

    val original = BitmapFactory.decodeFile(photoFile.absolutePath) ?: return
    try {
        val squareSide = (squareFractionOfHostHeight * original.height)
            .toInt()
            .coerceIn(1, minOf(original.width, original.height))
        val left = ((original.width - squareSide) / 2).coerceIn(0, original.width - squareSide)
        val top = ((original.height - squareSide) / 2).coerceIn(0, original.height - squareSide)

        val cropped = Bitmap.createBitmap(original, left, top, squareSide, squareSide)
        FileOutputStream(photoFile).use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        if (cropped !== original) cropped.recycle()
    } finally {
        original.recycle()
    }
}
