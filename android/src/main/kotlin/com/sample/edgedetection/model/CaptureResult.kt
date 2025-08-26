package com.sample.edgedetection.model

data class PointF(val x: Float, val y: Float)

data class CaptureResult(
    val imagePath: String,
    val corners: List<PointF> // order: TL, TR, BR, BL in image coordinates (full-res)
) {
    fun toMap(): Map<String, Any> = mapOf(
        "imagePath" to imagePath,
        "corners" to corners.map { listOf(it.x, it.y) }
    )
}
