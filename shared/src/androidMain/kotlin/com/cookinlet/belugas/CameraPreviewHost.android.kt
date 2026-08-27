package com.cookinlet.belugas

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.OrientationEventListener
import android.view.Surface
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
import androidx.compose.ui.layout.layout
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
import androidx.exifinterface.media.ExifInterface
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
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Tracks the device's physical rotation (independent of any UI orientation lock) so
    // ImageCapture's targetRotation -- and therefore the EXIF orientation baked into each
    // captured JPEG -- always matches how the phone is actually being held, not just whatever
    // rotation happened to be current when the use case was first built. The activity declares
    // configChanges for orientation (see AndroidManifest) so it's never recreated on rotation,
    // meaning nothing else would ever refresh this.
    var surfaceRotation by remember { mutableStateOf(Surface.ROTATION_0) }

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

    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientationDegrees: Int) {
                if (orientationDegrees == ORIENTATION_UNKNOWN) return
                surfaceRotation = when (orientationDegrees) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
        }, ContextCompat.getMainExecutor(context))
    }

    // (Re)bind Preview + ImageCapture whenever the provider, the view, the on-screen size, or
    // the device rotation change. hostSizePx changing is what fires on a device rotation (the
    // activity survives it, see the surfaceRotation comment above) -- rebinding through a
    // freshly-read previewView.viewPort each time is what keeps the ImageCapture crop matching
    // the *current* orientation's preview framing, instead of staying locked to whatever
    // orientation was active the first time this ran.
    LaunchedEffect(cameraProvider, previewView, hostSizePx, surfaceRotation) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        val size = hostSizePx ?: return@LaunchedEffect
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect

        imageCapture.targetRotation = surfaceRotation

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(view.surfaceProvider) }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            // previewView.viewPort is only non-null once the view has been laid out at least
            // once; fall back to a plain (unmatched) bind on the rare first-frame race rather
            // than skip binding the camera entirely.
            val viewPort = view.viewPort
            camera = if (viewPort != null) {
                val useCaseGroup = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(imageCapture)
                    .build()
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
            } else {
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Apply zoom ratio to camera hardware -- also re-applied after every (re)bind above, since
    // a rebind resets the hardware zoom but shouldn't visibly reset the on-screen zoom label.
    LaunchedEffect(camera, zoomRatio) {
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
                    }.also { previewView = it }
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

                // Rotated Slider to act as a vertical control. A Slider always maps its drag
                // range to its own pre-rotation *width*, not however long it looks on screen --
                // sizing it height(150).width(40) then rotating left the actual draggable range
                // confined to that 40dp width (looking, after rotation, like only a fraction of
                // the visible 150dp track was interactive). Sizing it width(150).height(40)
                // instead gives it the full 150dp as its drag axis; the `layout` modifier then
                // swaps what size the *parent* sees (40 wide x 150 tall) to match the rotated
                // visual footprint, and re-centers the placement to compensate for rotating
                // around a differently-shaped box than what the parent now reserves.
                Slider(
                    value = zoomRatio,
                    onValueChange = { zoomRatio = it },
                    valueRange = 1f..5f,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.height, placeable.width) {
                                placeable.place(
                                    x = -(placeable.width / 2 - placeable.height / 2),
                                    y = -(placeable.height / 2 - placeable.width / 2)
                                )
                            }
                        }
                        .graphicsLayer {
                            rotationZ = 270f
                        }
                        .width(150.dp)
                        .height(40.dp)
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
 * The square's side is the reticle's height (its constraining dimension, since the reticle box
 * is always wider than tall) and is centered on the same point the reticle is -- the
 * screen/host center. This produces the right region regardless of device orientation because:
 * Preview and ImageCapture are bound through a shared, rotation-aware ViewPort (see the binding
 * above, rebound on every orientation change), which keeps the saved photo's aspect ratio and
 * framing matched to what's actually shown on screen; and the EXIF correction below undoes the
 * rotation CameraX bakes into the JPEG's orientation tag, so the decoded bitmap's width/height
 * axes line up with the host's on-screen width/height axes rather than the sensor's fixed ones.
 */
private fun cropToReticleSquare(photoFile: File, hostSize: IntSize, density: Density) {
    if (hostSize.width <= 0 || hostSize.height <= 0) return

    val reticleHeightPx = with(density) { RETICLE_HEIGHT_DP.dp.toPx() }
    val squareFractionOfHostHeight = (reticleHeightPx / hostSize.height).coerceIn(0f, 1f)

    val decoded = BitmapFactory.decodeFile(photoFile.absolutePath) ?: return
    val rotationDegrees = try {
        ExifInterface(photoFile.absolutePath).rotationDegrees
    } catch (e: Exception) {
        0
    }
    val original = if (rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
    } else {
        decoded
    }

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
