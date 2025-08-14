package com.sample.edgedetection.view

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.common.StandardMessageCodec

class CropPlatformViewFactory(
    private val messenger: BinaryMessenger
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, id: Int, args: Any?): PlatformView {
        val channel = MethodChannel(messenger, "edge_detection/crop_view_$id")
        @Suppress("UNCHECKED_CAST")
        val params = args as? Map<String, Any>
        return CropPlatformView(context, channel, params)
    }
}
