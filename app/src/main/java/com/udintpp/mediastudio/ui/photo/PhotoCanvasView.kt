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
import com.udintpp.mediastudio.data.model.AspectRatio
import com.udintpp.mediastudio.data.model.ImageAdjustments
import com.udintpp.mediastudio.data.model.OverlayType
import com.udintpp.mediastudio.data.model.StickerItem
import com.udintpp.mediastudio.engine.photo.BackgroundRemover
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class BrushMode {
    NONE,
    RESTORE, // Kuas Pemulih: mengembalikan bagian yang terhapus dari foto asli
    ERASE    // Kuas Penghapus: menghapus sisa background yang belum bersih
}

class PhotoCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var baseBitmap: Bitmap? = null
        set(value) {
            field = value
            if (isCropModeActive) {
                initCropRect()
            }
            invalidate()
        }

    var originalUncutBitmap: Bitmap? = null

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

    // Brush Mode
    var brushMode: BrushMode = BrushMode.NONE
    var brushRadius: Float = 50f

    // Eyedropper ("Tandai Warna") Mode
    var isEyedropperActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var currentSampledColor: Int = Color.TRANSPARENT
    var onColorSampled: ((Int) -> Unit)? = null

    // Visual Cropper ("Penanda Crop") Mode
    var isCropModeActive: Boolean = false
        set(value) {
            field = value
            if (value) {
                initCropRect()
            }
            invalidate()
        }
    var cropAspectRatio: AspectRatio = AspectRatio.ORIGINAL
        set(value) {
            field = value
            adjustCropRectToRatio()
            invalidate()
        }

    val cropRect = RectF()
    private val cropHandleRadius = 32f
    private var activeCropHandle = -1 // 0=TL, 1=TR, 2=BR, 3=BL, 4=Inside move, -1=None

    val stickers = mutableListOf<StickerItem>()
    private var selectedSticker: StickerItem? = null

    // Paints
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
    private val brushCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8006B6D4")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // Cropper Paints
    private val cropScrimPaint = Paint().apply {
        color = Color.parseColor("#AA000000")
        style = Paint.Style.FILL
    }
    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val cropGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val cropHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val cropHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0284C7")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    // Eyedropper Loupe Paints
    private val loupeOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val loupeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val loupeCrosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val loupeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    // Touch tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isTouching = false
    private var initialDistance = 0f
    private var initialRotation = 0f
    private var initialScale = 1.0f

    val currentDstRect = RectF()

    init {
        updateColorFilter()
    }

    private fun updateColorFilter() {
        val cm = ColorMatrix()
        val sat = ColorMatrix()
        sat.setSaturation(adjustments.saturation)
        cm.postConcat(sat)

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

    fun initCropRect() {
        if (currentDstRect.width() > 0 && currentDstRect.height() > 0) {
            val insetX = currentDstRect.width() * 0.1f
            val insetY = currentDstRect.height() * 0.1f
            cropRect.set(
                currentDstRect.left + insetX,
                currentDstRect.top + insetY,
                currentDstRect.right - insetX,
                currentDstRect.bottom - insetY
            )
            adjustCropRectToRatio()
        }
    }

    private fun adjustCropRectToRatio() {
        if (cropAspectRatio == AspectRatio.ORIGINAL || cropAspectRatio.ratioX == 0f) return
        val targetRatio = cropAspectRatio.ratioX / cropAspectRatio.ratioY
        val currentW = cropRect.width()
        val currentH = cropRect.height()

        if (currentW / currentH > targetRatio) {
            val newW = currentH * targetRatio
            val cx = cropRect.centerX()
            cropRect.left = cx - newW / 2f
            cropRect.right = cx + newW / 2f
        } else {
            val newH = currentW / targetRatio
            val cy = cropRect.centerY()
            cropRect.top = cy - newH / 2f
            cropRect.bottom = cy + newH / 2f
        }
    }

    /**
     * Crops baseBitmap to the user's marked visual cropRect.
     */
    fun applyVisualCrop(): Bitmap? {
        val bmp = baseBitmap ?: return null
        if (currentDstRect.width() <= 0 || currentDstRect.height() <= 0) return null

        val scaleX = bmp.width.toFloat() / currentDstRect.width()
        val scaleY = bmp.height.toFloat() / currentDstRect.height()

        val bmpLeft = ((cropRect.left - currentDstRect.left) * scaleX).toInt().coerceIn(0, bmp.width - 1)
        val bmpTop = ((cropRect.top - currentDstRect.top) * scaleY).toInt().coerceIn(0, bmp.height - 1)
        val bmpRight = ((cropRect.right - currentDstRect.left) * scaleX).toInt().coerceIn(bmpLeft + 1, bmp.width)
        val bmpBottom = ((cropRect.bottom - currentDstRect.top) * scaleY).toInt().coerceIn(bmpTop + 1, bmp.height)

        val cropW = max(1, bmpRight - bmpLeft)
        val cropH = max(1, bmpBottom - bmpTop)

        val cropped = Bitmap.createBitmap(bmp, bmpLeft, bmpTop, cropW, cropH)
        baseBitmap = cropped
        isCropModeActive = false
        return cropped
    }

    fun addSticker(sticker: StickerItem) {
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

            if (bmpRatio > viewRatio) {
                val dstHeight = width / bmpRatio
                val top = (height - dstHeight) / 2f
                currentDstRect.set(0f, top, width.toFloat(), top + dstHeight)
            } else {
                val dstWidth = height * bmpRatio
                val left = (width - dstWidth) / 2f
                currentDstRect.set(left, 0f, left + dstWidth, height.toFloat())
            }

            canvas.drawBitmap(bmp, null, currentDstRect, basePaint)
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

            if (st.isSelected && brushMode == BrushMode.NONE && !isCropModeActive && !isEyedropperActive) {
                canvas.drawRect(-halfW - 8f, -halfH - 8f, halfW + 8f, halfH + 8f, borderPaint)
                canvas.drawCircle(-halfW - 8f, -halfH - 8f, 14f, handlePaint)
                canvas.drawCircle(halfW + 8f, halfH + 8f, 14f, handlePaint)
            }
            canvas.restore()
        }

        // 4. Draw Interactive Crop Overlay if Crop Mode is Active
        if (isCropModeActive) {
            drawCropOverlay(canvas)
        }

        // 5. Draw Brush Touch Cursor if in Brush Mode
        if (brushMode != BrushMode.NONE && isTouching) {
            canvas.drawCircle(lastTouchX, lastTouchY, brushRadius, brushCursorPaint)
        }

        // 6. Draw Eyedropper Magnifier Loupe if Eyedropper Mode is Active
        if (isEyedropperActive && isTouching) {
            drawEyedropperLoupe(canvas, lastTouchX, lastTouchY)
        }
    }

    private fun drawCropOverlay(canvas: Canvas) {
        // Scrim outside cropRect
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, cropScrimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), cropScrimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, cropScrimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, cropScrimPaint)

        // Main Crop Rectangle Border
        canvas.drawRect(cropRect, cropBorderPaint)

        // Rule of thirds grid
        val oneThirdW = cropRect.width() / 3f
        val oneThirdH = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + oneThirdW, cropRect.top, cropRect.left + oneThirdW, cropRect.bottom, cropGridPaint)
        canvas.drawLine(cropRect.left + oneThirdW * 2f, cropRect.top, cropRect.left + oneThirdW * 2f, cropRect.bottom, cropGridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + oneThirdH, cropRect.right, cropRect.top + oneThirdH, cropGridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + oneThirdH * 2f, cropRect.right, cropRect.top + oneThirdH * 2f, cropGridPaint)

        // Corner Handles
        drawCropHandle(canvas, cropRect.left, cropRect.top)
        drawCropHandle(canvas, cropRect.right, cropRect.top)
        drawCropHandle(canvas, cropRect.right, cropRect.bottom)
        drawCropHandle(canvas, cropRect.left, cropRect.bottom)
    }

    private fun drawCropHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, cropHandleRadius, cropHandleFillPaint)
        canvas.drawCircle(cx, cy, cropHandleRadius, cropHandleStrokePaint)
    }

    private fun drawEyedropperLoupe(canvas: Canvas, touchX: Float, touchY: Float) {
        val loupeRadius = 90f
        val loupeY = max(loupeRadius + 30f, touchY - 140f) // Display loupe above finger
        val loupeX = touchX.coerceIn(loupeRadius + 20f, width - loupeRadius - 20f)

        // Fill sampled color
        loupeFillPaint.color = currentSampledColor
        canvas.drawCircle(loupeX, loupeY, loupeRadius, loupeFillPaint)
        canvas.drawCircle(loupeX, loupeY, loupeRadius, loupeOuterPaint)

        // Crosshair
        canvas.drawLine(loupeX - 30f, loupeY, loupeX + 30f, loupeY, loupeCrosshairPaint)
        canvas.drawLine(loupeX, loupeY - 30f, loupeX, loupeY + 30f, loupeCrosshairPaint)

        // Color Hex text
        val hex = String.format("#%06X", 0xFFFFFF and currentSampledColor)
        canvas.drawText(hex, loupeX, loupeY + loupeRadius + 34f, loupeTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastTouchX = event.x
        lastTouchY = event.y

        // A. Eyedropper ("Tandai Warna") Mode
        if (isEyedropperActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isTouching = true
                    currentSampledColor = sampleColorAtScreen(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    isTouching = false
                    currentSampledColor = sampleColorAtScreen(event.x, event.y)
                    onColorSampled?.invoke(currentSampledColor)
                    invalidate()
                    return true
                }
            }
        }

        // B. Visual Crop Mode
        if (isCropModeActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeCropHandle = hitTestCropHandle(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeCropHandle != -1) {
                        handleCropDrag(event.x, event.y)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeCropHandle = -1
                    return true
                }
            }
        }

        // C. Brush Mode (Restore / Erase)
        if (brushMode != BrushMode.NONE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isTouching = true
                    applyBrushAtTouch(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    applyBrushAtTouch(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isTouching = false
                    invalidate()
                    return true
                }
            }
        }

        // D. Sticker Touch Handling
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
        }
        return true
    }

    private fun hitTestCropHandle(x: Float, y: Float): Int {
        val touchTolerance = cropHandleRadius * 2.2f
        if (hypot(x - cropRect.left, y - cropRect.top) <= touchTolerance) return 0 // TL
        if (hypot(x - cropRect.right, y - cropRect.top) <= touchTolerance) return 1 // TR
        if (hypot(x - cropRect.right, y - cropRect.bottom) <= touchTolerance) return 2 // BR
        if (hypot(x - cropRect.left, y - cropRect.bottom) <= touchTolerance) return 3 // BL
        if (cropRect.contains(x, y)) return 4 // Inside move
        return -1
    }

    private fun handleCropDrag(x: Float, y: Float) {
        val minSize = 100f
        when (activeCropHandle) {
            0 -> { // TL
                cropRect.left = min(x, cropRect.right - minSize).coerceAtLeast(currentDstRect.left)
                cropRect.top = min(y, cropRect.bottom - minSize).coerceAtLeast(currentDstRect.top)
            }
            1 -> { // TR
                cropRect.right = max(x, cropRect.left + minSize).coerceAtMost(currentDstRect.right)
                cropRect.top = min(y, cropRect.bottom - minSize).coerceAtLeast(currentDstRect.top)
            }
            2 -> { // BR
                cropRect.right = max(x, cropRect.left + minSize).coerceAtMost(currentDstRect.right)
                cropRect.bottom = max(y, cropRect.top + minSize).coerceAtMost(currentDstRect.bottom)
            }
            3 -> { // BL
                cropRect.left = min(x, cropRect.right - minSize).coerceAtLeast(currentDstRect.left)
                cropRect.bottom = max(y, cropRect.top + minSize).coerceAtMost(currentDstRect.bottom)
            }
            4 -> { // Move inside
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                val w = cropRect.width()
                val h = cropRect.height()
                var newLeft = cropRect.left + dx
                var newTop = cropRect.top + dy

                if (newLeft < currentDstRect.left) newLeft = currentDstRect.left
                if (newLeft + w > currentDstRect.right) newLeft = currentDstRect.right - w
                if (newTop < currentDstRect.top) newTop = currentDstRect.top
                if (newTop + h > currentDstRect.bottom) newTop = currentDstRect.bottom - h

                cropRect.set(newLeft, newTop, newLeft + w, newTop + h)
            }
        }
        adjustCropRectToRatio()
    }

    private fun sampleColorAtScreen(screenX: Float, screenY: Float): Int {
        val bmp = baseBitmap ?: return Color.BLACK
        if (currentDstRect.width() <= 0 || currentDstRect.height() <= 0) return Color.BLACK

        val bmpX = ((screenX - currentDstRect.left) * (bmp.width.toFloat() / currentDstRect.width())).toInt()
            .coerceIn(0, bmp.width - 1)
        val bmpY = ((screenY - currentDstRect.top) * (bmp.height.toFloat() / currentDstRect.height())).toInt()
            .coerceIn(0, bmp.height - 1)

        return bmp.getPixel(bmpX, bmpY)
    }

    private fun applyBrushAtTouch(screenX: Float, screenY: Float) {
        val bmp = baseBitmap ?: return
        if (currentDstRect.width() <= 0 || currentDstRect.height() <= 0) return

        val bmpX = (screenX - currentDstRect.left) * (bmp.width.toFloat() / currentDstRect.width())
        val bmpY = (screenY - currentDstRect.top) * (bmp.height.toFloat() / currentDstRect.height())
        val bmpBrushRad = brushRadius * (bmp.width.toFloat() / currentDstRect.width())

        if (brushMode == BrushMode.RESTORE) {
            val orig = originalUncutBitmap ?: return
            BackgroundRemover.restoreBrush(bmp, orig, bmpX, bmpY, bmpBrushRad)
        } else if (brushMode == BrushMode.ERASE) {
            BackgroundRemover.eraseBrush(bmp, bmpX, bmpY, bmpBrushRad)
        }
    }

    private fun hypot(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

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
