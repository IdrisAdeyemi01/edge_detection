package com.sample.edgedetection.view

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.SurfaceView
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.platform.PlatformView
import com.sample.edgedetection.scan.ScanPresenter
import com.sample.edgedetection.scan.IScanView
import com.sample.edgedetection.R
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import com.sample.edgedetection.EdgeDetectionHandler
import org.opencv.android.OpenCVLoader
import android.os.Handler
import android.os.Looper
import android.util.Log


class CameraPlatformView(
    private val activity: Activity,
    context: Context,
    messenger: BinaryMessenger,
    id: Int,
    params: Map<String, Any>?

) : PlatformView, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private val TAG = "CameraPlatformView"
    private val rootView: View
    private val methodChannel: MethodChannel
    private var scanPresenter: ScanPresenter? = null

    private val loaderCallback = object : BaseLoaderCallback(activity) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                LoaderCallbackInterface.SUCCESS -> {
                    Log.i(TAG, "OpenCV loaded successfully (BaseLoaderCallback).")
                    // Create presenter now that OpenCV is ready
                    createPresenterIfNeeded(params)
                }
                else -> {
                    super.onManagerConnected(status)
                    Log.e(TAG, "OpenCV failed to load with status $status")
                }
            }
        }
    }

    init {
        // inflate a layout similar to activity_scan.xml but without an ActionBar
        rootView = LayoutInflater.from(context).inflate(R.layout.view_scan, null, false)

        methodChannel = MethodChannel(messenger, "edge_detection/camera_view_$id")
        methodChannel.setMethodCallHandler(this)

        val ok = OpenCVLoader.initDebug()
        if (ok) {
            Log.i(TAG, "OpenCV initDebug() returned true.")
            // Safe to create presenter immediately (on UI thread).
            createPresenterIfNeeded(params)
        } else {
            Log.i(TAG, "OpenCV initDebug() failed — trying async init.")
            // Async init (this will call loaderCallback.onManagerConnected on UI thread)
            // Note: this requires OpenCV Manager or packaged libs — still works if packaged .so present.
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, activity.applicationContext, loaderCallback)
        }
    }

    private fun createPresenterIfNeeded(params: Map<String, Any>?) {
        // ensure this runs on UI thread
        Handler(Looper.getMainLooper()).post {
            if (scanPresenter != null) return@post

            // create the proxy for IScanView.Proxy
            val proxy = object : IScanView.Proxy {
                override fun exit() { /* no-op for embedded view */ }
                override fun getSurfaceView(): SurfaceView = rootView.findViewById(R.id.surface)
                override fun getPaperRect() = rootView.findViewById<com.sample.edgedetection.view.PaperRectangle>(R.id.paper_rect)
                override fun getCurrentDisplay() = activity.display ?: activity.windowManager.defaultDisplay
            }

            // build initial bundle from params if needed
            val bundle = Bundle().apply{
                params?.let {
                    putBoolean("can_use_gallery", it["can_use_gallery"] as? Boolean ?: true)
                    putString("scan_title", it["scan_title"] as? String ?: "")
                    putString("crop_title", it["crop_title"] as? String ?: "")
                    (it["save_to"] as? String)?.let { p -> putString(EdgeDetectionHandler.SAVE_TO, p) }
                }

            }
            // now it's safe to create the presenter (it will create Mat etc.)
            scanPresenter = ScanPresenter(activity, proxy, bundle).apply {
                setOnCaptureResultListener { res ->
                    // Send a single JSON object to Flutter
                    val payload = mapOf(
                        "imagePath" to res.imagePath,
                        "width" to res.width,
                        "height" to res.height,
                        "corners" to res.corners.map { mapOf("x" to it.x, "y" to it.y) },
                        "cornersNormalized" to res.cornersNormalized.map { mapOf("x" to it.x, "y" to it.y) }
                    )
                    methodChannel.invokeMethod("onCaptureResult", payload)
                }
            }

            methodChannel.setMethodCallHandler(this)
            // start preview after creation
            scanPresenter?.start()
        }
    }

    override fun getView(): View = rootView

    override fun dispose() {
        scanPresenter?.stop()
        scanPresenter?.release()
        methodChannel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "capture" -> {
                // If capture is called, then it means a capture button was clicked
                scanPresenter?.manualCapture()
                // call presenter's capture method. We'll implement a callback to return path (see Step 3).
//                scanPresenter?.shut() // or scanPresenter?.capture()
                // If the presenter/crop logic returns asynchronously with a path, you'll need to pass a callback
                // For now, return success boolean and later wire the callback to return the path.
                result.success(null)
            }
            "toggleFlash" -> {
                scanPresenter?.toggleFlash()
                result.success(null)
            }
            "stop" -> {
                scanPresenter?.stop()
                result.success(null)
            }
            "start" -> {
                scanPresenter?.start()
                result.success(null)
            }
            "setAutoCaptureEnabled" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                scanPresenter?.setAutoCaptureEnabled(enabled)
                result.success(null)
            }
            "setAutoCaptureStability" -> {
                val minStableFrames = call.argument<Int>("minStableFrames") ?: 6
                val maxCornerMove = call.argument<Double>("maxCornerMove") ?: 12.0
                val minQuadArea = call.argument<Double>("minQuadArea") ?: 0.1
                scanPresenter?.setAutoCaptureStability(
                    minStableFrames,
                    maxCornerMove.toFloat(),
                    minQuadArea.toFloat()
                )
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }
}
