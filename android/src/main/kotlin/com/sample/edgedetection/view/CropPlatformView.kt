package com.sample.edgedetection.view


import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import com.sample.edgedetection.EdgeDetectionHandler
import com.sample.edgedetection.R
import com.sample.edgedetection.view.PaperRectangle
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import com.sample.edgedetection.crop.CropPresenter
import com.sample.edgedetection.crop.ICropView

class CropPlatformView(
    private val context: Context,
    private val channel: MethodChannel,
    private val args: Map<String, Any>?
) : PlatformView {

    private val rootView: View
    private val presenter: CropPresenter
    private var showMenuItems = false

    init {
        // Inflate the existing view_crop.xml layout
        rootView = LayoutInflater.from(context).inflate(R.layout.view_crop, null)

        // Get initial bundle args from Flutter
        val bundle = Bundle().apply {
            putString(
                EdgeDetectionHandler.CROP_TITLE,
                args?.get("cropTitle") as? String ?: "Crop Document"
            )
            putString(
                EdgeDetectionHandler.IMAGE_PATH,
                args?.get("imagePath") as? String
            )
            putString(
                EdgeDetectionHandler.SAVE_PATH,
                args?.get("savePath") as? String
            )
        }

        presenter = CropPresenter(object : ICropView.Proxy {
            override fun getPaper(): ImageView = rootView.findViewById(R.id.paper)
            override fun getPaperRect(): PaperRectangle = rootView.findViewById(R.id.paper_rect)
            override fun getCroppedPaper(): ImageView = rootView.findViewById(R.id.picture_cropped)
        }, bundle)

        // Wait until view is drawn to init
        rootView.findViewById<View>(R.id.paper).post {
            presenter.onViewsReady(
                rootView.findViewById<View>(R.id.paper).width,
                rootView.findViewById<View>(R.id.paper).height
            )
        }

        // Hook crop button
        rootView.findViewById<ImageView>(R.id.crop).setOnClickListener {
            presenter.crop()
            showMenuItems = true
            // Notify Flutter
            channel.invokeMethod("onCropped", null)
        }

        // Method calls from Flutter
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "rotate" -> {
                    presenter.rotate()
                    result.success(null)
                }
                "enhance" -> {
                    presenter.enhance()
                    result.success(null)
                }
                "reset" -> {
                    presenter.reset()
                    result.success(null)
                }
                "save" -> {
                    presenter.save()
                    result.success(args?.get("savePath") as? String)
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun getView(): View = rootView
    override fun dispose() {}
}
