package com.udintpp.mediastudio.data.model

import android.graphics.Bitmap
import android.graphics.Matrix

enum class OverlayType {
    EMOJI,
    STICKER_PNG,
    STICKER_GIF,
    TEXT
}

data class StickerItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: OverlayType,
    var bitmap: Bitmap? = null,
    var text: String? = null,
    var x: Float = 0f,
    var y: Float = 0f,
    var scale: Float = 1.0f,
    var rotation: Float = 0f,
    var alpha: Float = 1.0f,
    var isSelected: Boolean = false,
    val matrix: Matrix = Matrix()
) {
    fun updateMatrix() {
        matrix.reset()
        val width = bitmap?.width?.toFloat() ?: 100f
        val height = bitmap?.height?.toFloat() ?: 100f
        matrix.postTranslate(-width / 2f, -height / 2f)
        matrix.postScale(scale, scale)
        matrix.postRotate(rotation)
        matrix.postTranslate(x, y)
    }
}
