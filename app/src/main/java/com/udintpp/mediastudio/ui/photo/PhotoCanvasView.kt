package com.udintpp.mediastudio.ui.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.udintpp.mediastudio.data.model.ImageAdjustments
import com.udintpp.mediastudio.data.model.OverlayType
import com.udintpp.mediastudio.data.model.StickerItem
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class PhotoCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var baseBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var backgroundBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var backgroundColorOverride: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    var adjustments: ImageAdjustments = ImageAdjustments()
        set(value) {
            field = value
            updateColorFilter()
            invalidate()
        }

    val stickers = mutableListOf<StickerItem>()
    private var selectedSticker: StickerItem? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stickerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6366F1")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EC4899")
        style = Paint.Style.FILL
    }

    // Touch gesture tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialDistance = 0f
    private var initialRotation = 0f
    private var initialScale = 1.0f

    init {
        updateColorFilter()
    }

    private fun updateColorFilter() {
        val cm = ColorMatrix()
        // Saturation
        val sat = ColorMatrix()
        sat.setSaturation(adjustments.saturation)
        cm.postConcat(sat)

        // Contrast & Brightness
        val c = adjustments.contrast
        val b = adjustments.brightness
        val translate = (1f - c) * 128f + b
        val cbMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(cbMatrix)
        basePaint.colorFilter = ColorMatrixColorFilter(cm)
    }

    fun addSticker(sticker: StickerItem) {
        // Place sticker at center by default
        if (sticker.x == 0f && sticker.y == 0f) {
            sticker.x = width / 2f
            sticker.y = height / 2f
        }
        stickers.forEach { it.isSelected = false }
        sticker.isSelected = true
        selectedSticker = sticker
        stickers.add(sticker)
        invalidate()
    }

    fun removeSelectedSticker() {
        selectedSticker?.let {
            stickers.remove(it)
            selectedSticker = null
            invalidate()
        }
    }

    fun clearAllStickers() {
        stickers.clear()
        selectedSticker = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw custom background color or background image if present
        if (backgroundColorOverride != null) {
            canvas.drawColor(backgroundColorOverride!!)
        } else if (backgroundBitmap != null) {
            val bg = backgroundBitmap!!
            val src = Rect(0, 0, bg.width, bg.height)
            val dst = Rect(0, 0, width, height)
            canvas.drawBitmap(bg, src, dst, basePaint)
        }

        // 2. Draw base photo centered and aspect-fit
        baseBitmap?.let { bmp ->
            val bmpRatio = bmp.width.toFloat() / bmp.height.toFloat()
            val viewRatio = width.toFloat() / height.toFloat()

            val dstRect = if (bmpRatio > viewRatio) {
                val dstHeight = width / bmpRatio
                val top = (height - dstHeight) / 2f
                RectF(0f, top, width.toFloat(), top + dstHeight)
            } else {
                val dstWidth = height * bmpRatio
                val left = (width - dstWidth) / 2f
                RectF(left, 0f, left + dstWidth, height.toFloat())
            }

            canvas.drawBitmap(bmp, null, dstRect, basePaint)
        }

        // 3. Draw Stickers & Overlays
        for (st in stickers) {
            val bmp = st.bitmap ?: continue
            canvas.save()
            canvas.translate(st.x, st.y)
            canvas.rotate(st.rotation)
            canvas.scale(st.scale, st.scale)

            stickerPaint.alpha = (st.alpha * 255).toInt()
            val halfW = bmp.width / 2f
            val halfH = bmp.height / 2f
            canvas.drawBitmap(bmp, -halfW, -halfH, stickerPaint)

            // Selection border and handles
            if (st.isSelected) {
                canvas.drawRect(-halfW - 8f, -halfH - 8f, halfW + 8f, halfH + 8f, borderPaint)
                // Draw corner handles
                canvas.drawCircle(-halfW - 8f, -halfH - 8f, 14f, handlePaint)
                canvas.drawCircle(halfW + 8f, halfH + 8f, 14f, handlePaint)
            }
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y

                // Hit test stickers in reverse order (top to bottom)
                var touchedSticker: StickerItem? = null
                for (i in stickers.indices.reversed()) {
                    val st = stickers[i]
                    val bmp = st.bitmap ?: continue
                    val dx = event.x - st.x
                    val dy = event.y - st.y
                    val halfW = (bmp.width * st.scale) / 2f
                    val halfH = (bmp.height * st.scale) / 2f

                    if (dx >= -halfW - 20f && dx <= halfW + 20f && dy >= -halfH - 20f && dy <= halfH + 20f) {
                        touchedSticker = st
                        break
                    }
                }

                stickers.forEach { it.isSelected = false }
                if (touchedSticker != null) {
                    touchedSticker.isSelected = true
                    selectedSticker = touchedSticker
                    // Move touched sticker to top
                    stickers.remove(touchedSticker)
                    stickers.add(touchedSticker)
                } else {
                    selectedSticker = null
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && selectedSticker != null) {
                    initialDistance = getFingerDistance(event)
                    initialRotation = getFingerAngle(event)
                    initialScale = selectedSticker!!.scale
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (selectedSticker != null) {
                    if (event.pointerCount == 1) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        selectedSticker!!.x += dx
                        selectedSticker!!.y += dy
                        lastTouchX = event.x
                        lastTouchY = event.y
                    } else if (event.pointerCount == 2) {
                        val currentDist = getFingerDistance(event)
                        val currentAngle = getFingerAngle(event)

                        if (initialDistance > 10f) {
                            val scaleRatio = currentDist / initialDistance
                            selectedSticker!!.scale = (initialScale * scaleRatio).coerceIn(0.2f, 5.0f)
                        }

                        val angleDiff = currentAngle - initialRotation
                        selectedSticker!!.rotation += angleDiff
                        initialRotation = currentAngle
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // Done
            }
        }
        return true
    }

    private fun getFingerDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun getFingerAngle(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }
}
