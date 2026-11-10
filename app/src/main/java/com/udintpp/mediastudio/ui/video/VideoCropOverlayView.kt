package com.udintpp.mediastudio.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class VideoCropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Crop State
    var isCropModeActive: Boolean = false
        set(value) {
            field = value
            if (value && cropRect.isEmpty) {
                initCropRect()
            }
            invalidate()
        }

    val cropRect = RectF()
    private val handleRadius = 30f
    private var activeHandle = -1 // 0=TL, 1=TR, 2=BR, 3=BL, 4=Inside, -1=None

    // Eyedropper State
    var isEyedropperActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var currentSampledColor: Int = Color.GREEN
    var onColorPicked: ((Int) -> Unit)? = null
    var currentVideoFrameBitmap: Bitmap? = null

    // Paints
    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0284C7")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    // Loupe Paints
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

    private var lastX = 0f
    private var lastY = 0f
    private var isTouching = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cropRect.isEmpty && w > 0 && h > 0) {
            initCropRect()
        }
    }

    fun initCropRect() {
        if (width > 0 && height > 0) {
            val insetX = width * 0.1f
            val insetY = height * 0.1f
            cropRect.set(insetX, insetY, width - insetX, height - insetY)
            invalidate()
        }
    }

    /**
     * Normalized crop rect (0.0 to 1.0)
     */
    fun getNormalizedCropRect(): RectF {
        if (width <= 0 || height <= 0) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            cropRect.left / width.toFloat(),
            cropRect.top / height.toFloat(),
            cropRect.right / width.toFloat(),
            cropRect.bottom / height.toFloat()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Crop Box
        if (isCropModeActive && !cropRect.isEmpty) {
            // Scrim
            canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, scrimPaint)
            canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), scrimPaint)
            canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint)
            canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, scrimPaint)

            // Border
            canvas.drawRect(cropRect, borderPaint)

            // Grid
            val qw = cropRect.width() / 3f
            val qh = cropRect.height() / 3f
            canvas.drawLine(cropRect.left + qw, cropRect.top, cropRect.left + qw, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left + qw * 2, cropRect.top, cropRect.left + qw * 2, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left, cropRect.top + qh, cropRect.right, cropRect.top + qh, gridPaint)
            canvas.drawLine(cropRect.left, cropRect.top + qh * 2, cropRect.right, cropRect.top + qh * 2, gridPaint)

            // Handles
            drawHandle(canvas, cropRect.left, cropRect.top)
            drawHandle(canvas, cropRect.right, cropRect.top)
            drawHandle(canvas, cropRect.right, cropRect.bottom)
            drawHandle(canvas, cropRect.left, cropRect.bottom)
        }

        // Draw Eyedropper Loupe
        if (isEyedropperActive && isTouching) {
            drawLoupe(canvas, lastX, lastY)
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, handleRadius, handleFillPaint)
        canvas.drawCircle(cx, cy, handleRadius, handleStrokePaint)
    }

    private fun drawLoupe(canvas: Canvas, x: Float, y: Float) {
        val loupeRadius = 90f
        val ly = max(loupeRadius + 30f, y - 130f)
        val lx = x.coerceIn(loupeRadius + 20f, width - loupeRadius - 20f)

        loupeFillPaint.color = currentSampledColor
        canvas.drawCircle(lx, ly, loupeRadius, loupeFillPaint)
        canvas.drawCircle(lx, ly, loupeRadius, loupeOuterPaint)

        canvas.drawLine(lx - 30f, ly, lx + 30f, ly, loupeCrosshairPaint)
        canvas.drawLine(lx, ly - 30f, lx, ly + 30f, loupeCrosshairPaint)

        val hex = String.format("#%06X", 0xFFFFFF and currentSampledColor)
        canvas.drawText(hex, lx, ly + loupeRadius + 34f, loupeTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastX = event.x
        lastY = event.y

        // Eyedropper Handling
        if (isEyedropperActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isTouching = true
                    currentSampledColor = sampleFrameColor(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    isTouching = false
                    currentSampledColor = sampleFrameColor(event.x, event.y)
                    onColorPicked?.invoke(currentSampledColor)
                    invalidate()
                    return true
                }
            }
        }

        // Crop Dragging Handling
        if (isCropModeActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeHandle = hitTestHandle(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeHandle != -1) {
                        handleDrag(event.x, event.y)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeHandle = -1
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    private fun hitTestHandle(x: Float, y: Float): Int {
        val tol = handleRadius * 2.5f
        if (hypot(x - cropRect.left, y - cropRect.top) <= tol) return 0 // TL
        if (hypot(x - cropRect.right, y - cropRect.top) <= tol) return 1 // TR
        if (hypot(x - cropRect.right, y - cropRect.bottom) <= tol) return 2 // BR
        if (hypot(x - cropRect.left, y - cropRect.bottom) <= tol) return 3 // BL
        if (cropRect.contains(x, y)) return 4 // Inside
        return -1
    }

    private fun handleDrag(x: Float, y: Float) {
        val minSize = 100f
        when (activeHandle) {
            0 -> { // TL
                cropRect.left = min(x, cropRect.right - minSize).coerceAtLeast(0f)
                cropRect.top = min(y, cropRect.bottom - minSize).coerceAtLeast(0f)
            }
            1 -> { // TR
                cropRect.right = max(x, cropRect.left + minSize).coerceAtMost(width.toFloat())
                cropRect.top = min(y, cropRect.bottom - minSize).coerceAtLeast(0f)
            }
            2 -> { // BR
                cropRect.right = max(x, cropRect.left + minSize).coerceAtMost(width.toFloat())
                cropRect.bottom = max(y, cropRect.top + minSize).coerceAtMost(height.toFloat())
            }
            3 -> { // BL
                cropRect.left = min(x, cropRect.right - minSize).coerceAtLeast(0f)
                cropRect.bottom = max(y, cropRect.top + minSize).coerceAtMost(height.toFloat())
            }
            4 -> { // Move inside
                val dx = x - lastX
                val dy = y - lastY
                val w = cropRect.width()
                val h = cropRect.height()
                var newL = cropRect.left + dx
                var newT = cropRect.top + dy

                if (newL < 0) newL = 0f
                if (newL + w > width) newL = width - w
                if (newT < 0) newT = 0f
                if (newT + h > height) newT = height - h

                cropRect.set(newL, newT, newL + w, newT + h)
            }
        }
    }

    private fun sampleFrameColor(sx: Float, sy: Float): Int {
        val bmp = currentVideoFrameBitmap ?: return Color.GREEN
        if (width <= 0 || height <= 0) return Color.GREEN

        val bx = ((sx / width.toFloat()) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val by = ((sy / height.toFloat()) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        return bmp.getPixel(bx, by)
    }

    private fun hypot(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)
}
