package io.ymusic.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIViewController
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
internal suspend fun iosScanQrCode(): String? {
    val granted = cameraGranted()
    if (!granted) return null
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val host = topViewController()
            if (host == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val scanner = IosQrScannerController { value ->
                if (continuation.isActive) continuation.resume(value)
            }
            host.presentViewController(scanner, animated = true, completion = null)
            continuation.invokeOnCancellation {
                scanner.dismissViewControllerAnimated(true, completion = null)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun cameraGranted(): Boolean = suspendCancellableCoroutine { continuation ->
    val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
    when (status) {
        AVAuthorizationStatusAuthorized -> continuation.resume(true)
        AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted -> continuation.resume(false)
        else -> AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
            continuation.resume(granted)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) {
        vc = vc?.presentedViewController
    }
    return vc
}

@OptIn(ExperimentalForeignApi::class)
private class IosQrScannerController(
    private val onResult: (String?) -> Unit,
) : UIViewController(nibName = null, bundle = null), AVCaptureMetadataOutputObjectsDelegateProtocol {

    private val captureSession = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer? = null
    private var finished = false

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        if (device == null) {
            finish(null)
            return
        }
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null)
        if (input == null || !captureSession.canAddInput(input)) {
            finish(null)
            return
        }
        captureSession.addInput(input)
        val output = AVCaptureMetadataOutput()
        if (!captureSession.canAddOutput(output)) {
            finish(null)
            return
        }
        captureSession.addOutput(output)
        output.setMetadataObjectsDelegate(this, queue = dispatch_get_main_queue())
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        val preview = AVCaptureVideoPreviewLayer(session = captureSession)
        preview.videoGravity = AVLayerVideoGravityResizeAspectFill
        view.layer.addSublayer(preview)
        previewLayer = preview

        val close = UIButton.buttonWithType(UIButtonTypeSystem)
        close.setTitle("✕", UIControlStateNormal)
        close.setTitleColor(UIColor.whiteColor, UIControlStateNormal)
        close.addTarget(this, NSSelectorFromString("onClose"), UIControlEventTouchUpInside)
        close.setFrame(CGRectMake(16.0, 48.0, 44.0, 44.0))
        view.addSubview(close)

        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            captureSession.startRunning()
        }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.setFrame(view.bounds)
    }

    @ObjCAction
    fun onClose() {
        finish(null)
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        val code = didOutputMetadataObjects
            .mapNotNull { it as? AVMetadataMachineReadableCodeObject }
            .firstNotNullOfOrNull { it.stringValue }
        if (code != null) finish(code)
    }

    private fun finish(value: String?) {
        if (finished) return
        finished = true
        captureSession.stopRunning()
        dismissViewControllerAnimated(true) { onResult(value) }
    }
}
