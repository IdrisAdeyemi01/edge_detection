package com.sample.edgedetection.view

import android.app.Activity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import com.sample.edgedetection.view.CameraPlatformView


class CameraPlatformViewFactory(
    private val messenger: BinaryMessenger,
    private val activity: Activity
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: android.content.Context, id: Int, args: Any?): PlatformView {
        val params = args as? Map<String, Any>
        return CameraPlatformView(activity, context, messenger, id, params)
    }
}
