package com.cookinlet.belugas

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.*
import platform.Foundation.*
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraPreviewHost(
    modifier: Modifier,
    takePhotoSignal: Boolean,
    onPhotoCaptured: (String) -> Unit
) {
    var cameraView by remember { mutableStateOf<UIView?>(null) }
    var photoOutput by remember { mutableStateOf<AVCapturePhotoOutput?>(null) }
    val photoDelegate = remember { PhotoCaptureDelegate(onPhotoCaptured) }

    LaunchedEffect(Unit) {
        val view = UIView().apply { backgroundColor = UIColor.blackColor }
        val captureSession = AVCaptureSession().apply { sessionPreset = AVCaptureSessionPresetPhoto }

        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        if (device != null) {
            val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)
            if (input != null && captureSession.canAddInput(input)) {
                captureSession.addInput(input)
            }

            val output = AVCapturePhotoOutput()
            if (captureSession.canAddOutput(output)) {
                captureSession.addOutput(output)
                photoOutput = output
            }

            val previewLayer = AVCaptureVideoPreviewLayer.layerWithSession(captureSession).apply {
                videoGravity = AVLayerVideoGravityResizeAspectFill
                frame = view.bounds
            }
            view.layer.addSublayer(previewLayer)
            captureSession.startRunning()
        }
        cameraView = view
    }

    // Trigger Photo Snapshot on iOS
    LaunchedEffect(takePhotoSignal) {
        if (takePhotoSignal && photoOutput != null) {
            val settings = AVCapturePhotoSettings.photoSettingsWithFormat(
                mapOf(AVVideoCodecKey to AVVideoCodecTypeJPEG)
            )
            photoOutput?.capturePhotoWithSettings(settings, photoDelegate)
        }
    }

    cameraView?.let { view ->
        UIKitView(
            factory = { view },
            modifier = modifier,
            update = {
                it.layer.sublayers?.firstOrNull()?.let { layer ->
                    if (layer is platform.QuartzCore.CALayer) {
                        layer.frame = it.bounds
                    }
                }
            }
        )
    }
}

// Delegate helper to process the captured iOS photo buffer and write JPEG to temporary file
private class PhotoCaptureDelegate(
    private val onCaptured: (String) -> Unit
) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {

    @OptIn(ExperimentalForeignApi::class)
    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?
    ) {
        if (error == null) {
            val photoData = didFinishProcessingPhoto.fileDataRepresentation()
            if (photoData != null) {
                val tempDir = NSTemporaryDirectory()
                val filePath = "${tempDir}BELUGA_${NSDate().timeIntervalSince1970}.jpg"
                
                photoData.writeToFile(filePath, true)
                onCaptured(filePath)
            }
        }
    }
}
