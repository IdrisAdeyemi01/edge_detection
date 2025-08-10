package com.sample.edgedetection.view

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.SurfaceView
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import com.sample.edgedetection.scan.ScanPresenter
import com.sample.edgedetection.scan.IScanView

class CameraPlatformView(
    context: Context,
    messenger: BinaryMessenger,
    id: Int,
    params: Map<String, Any>?
) : PlatformView, MethodChannel.MethodCallHandler {

    private val rootView: View
    private val methodChannel: MethodChannel
    private var scanPresenter: ScanPresenter? = null
    private val activity: Activity = (context as? Activity) ?: throw IllegalStateException("Activity required")

    init {
        // inflate a layout similar to activity_scan.xml but without an ActionBar
        rootView = LayoutInflater.from(context).inflate(R.layout.view_scan, null, false)

        // create the proxy for IScanView.Proxy
        val proxy = object : IScanView.Proxy {
            override fun getSurfaceView(): SurfaceView = rootView.findViewById(R.id.surface)
            override fun getPaperRect() = rootView.findViewById<com.sample.edgedetection.view.PaperRectangle>(R.id.paper_rect)
            override fun getCurrentDisplay() = activity.display ?: activity.windowManager.defaultDisplay
        }

        // convert params -> Bundle
        val bundle = Bundle()
        params?.let {
            bundle.putBoolean("can_use_gallery", it["can_use_gallery"] as? Boolean ?: true)
            bundle.putString("scan_title", it["scan_title"] as? String ?: "")
            bundle.putString("crop_title", it["crop_title"] as? String ?: "")
            // ... add other params similarly
        }

        // create presenter the same way ScanActivity does:
        scanPresenter = ScanPresenter(activity, proxy, bundle)

        methodChannel = MethodChannel(messenger, "edge_detection/camera_view_$id")
        methodChannel.setMethodCallHandler(this)

        // start preview
        scanPresenter?.start()

        scanPresenter.setOnResultListener { savedPath ->
            methodChannel.invokeMethod("onImageCaptured", savedPath)
        }


    }

    override fun getView(): View = rootView

    override fun dispose() {
        scanPresenter?.stop()
        methodChannel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodChannel.MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "capture" -> {
                // call presenter's capture method. We'll implement a callback to return path (see Step 3).
                scanPresenter?.shut() // or scanPresenter?.capture()
                // If the presenter/crop logic returns asynchronously with a path, you'll need to pass a callback
                // For now, return success boolean and later wire the callback to return the path.
                scanPresenter.setOnResultListener {
                    path -> methodChannel.invokeMethod("onImageCaptured", path)
                }
                result.success(true)
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


            else -> result.notImplemented()
        }
    }
}
