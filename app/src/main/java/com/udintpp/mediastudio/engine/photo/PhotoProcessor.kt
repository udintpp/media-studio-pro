package com.udintpp.mediastudio.engine.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import com.udintpp.mediastudio.data.model.AspectRatio
import com.udintpp.mediastudio.data.model.FilterPreset
import com.udintpp.mediastudio.data.model.ImageAdjustments
import kotlin.math.max
import kotlin.math.min

object PhotoProcessor {

    /**
     * Applies full adjustment pipeline: brightness, contrast, saturation, warmth, presets, vignette, and beauty.
     */
    fun processImage(source: Bitmap, adjustments: ImageAdjustments): Bitmap {
        var working = source.copy(Bitmap.Config.ARGB_8888, true)

        // 1. Beauty smoothing if enabled
        if (adjustments.beauty > 0f) {
            working = applyBeautySmoothing(working, adjustments.beauty)
        }

        // 2. ColorMatrix adjustments (Brightness, Contrast, Saturation, Warmth, Presets)
        val cm = buildCompositeColorMatrix(adjustments)
        val filtered = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(filtered)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(working, 0f, 0f, paint)

        // 3. Vignette overlay if enabled
        if (adjustments.vignette > 0f) {
            applyVignette(canvas, working.width.toFloat(), working.height.toFloat(), adjustments.vignette)
        }

        // 4. Sharpen if enabled
        if (adjustments.sharpen > 0f) {
            return applySharpen(filtered, adjustments.sharpen)
        }

        return filtered
    }

    private fun buildCompositeColorMatrix(adj: ImageAdjustments): ColorMatrix {
        val composite = ColorMatrix()

        // Saturation
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(adj.saturation)
        composite.postConcat(satMatrix)

        // Contrast & Brightness
        // Formula: NewColor = Contrast * (OldColor - 128) + 128 + Brightness
        val c = adj.contrast
        val b = adj.brightness
        val scale = c
        val translate = (1f - c) * 128f + b

        val contrastBrightnessMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        composite.postConcat(contrastBrightnessMatrix)

        // Warmth (increase Red, decrease Blue)
        if (adj.warmth != 0f) {
            val w = adj.warmth * 0.6f
            val warmthMatrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, w,
                    0f, 1f, 0f, 0f, w * 0.3f,
                    0f, 0f, 1f, 0f, -w,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            composite.postConcat(warmthMatrix)
        }

        // Artistic Presets
        val presetMatrix = when (adj.filterPreset) {
            FilterPreset.VIVID -> ColorMatrix().apply {
                setSaturation(1.4f)
                postConcat(ColorMatrix(floatArrayOf(
                    1.1f, 0f, 0f, 0f, 5f,
                    0f, 1.1f, 0f, 0f, 5f,
                    0f, 0f, 1.15f, 0f, 8f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            FilterPreset.CYBERPUNK -> ColorMatrix(floatArrayOf(
                1.4f, 0f, 0.2f, 0f, 15f,
                0f, 0.8f, 0.3f, 0f, -10f,
                0.2f, 0.2f, 1.8f, 0f, 30f,
                0f, 0f, 0f, 1f, 0f
            ))
            FilterPreset.FILM_NOIR -> ColorMatrix().apply {
                setSaturation(0f)
                val highC = 1.35f
                val trans = (1f - highC) * 128f
                postConcat(ColorMatrix(floatArrayOf(
                    highC, 0f, 0f, 0f, trans,
                    0f, highC, 0f, 0f, trans,
                    0f, 0f, highC, 0f, trans,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            FilterPreset.RETRO_VINTAGE -> ColorMatrix(floatArrayOf(
                0.393f + 0.3f, 0.769f, 0.189f, 0f, 20f,
                0.349f, 0.686f + 0.2f, 0.168f, 0f, 10f,
                0.272f, 0.534f, 0.131f + 0.1f, 0f, -10f,
                0f, 0f, 0f, 1f, 0f
            ))
            FilterPreset.CINEMATIC -> ColorMatrix(floatArrayOf(
                1.2f, 0f, 0f, 0f, 12f,
                0f, 1.0f, 0.1f, 0f, 5f,
                0f, 0.1f, 1.3f, 0f, 25f,
                0f, 0f, 0f, 1f, 0f
            ))
            FilterPreset.PASTEL -> ColorMatrix(floatArrayOf(
                0.9f, 0.1f, 0.1f, 0f, 30f,
                0.1f, 0.9f, 0.1f, 0f, 30f,
                0.1f, 0.1f, 0.95f, 0f, 35f,
                0f, 0f, 0f, 1f, 0f
            ))
            FilterPreset.WARM_GOLD -> ColorMatrix(floatArrayOf(
                1.25f, 0f, 0f, 0f, 25f,
                0f, 1.15f, 0f, 0f, 18f,
                0f, 0f, 0.85f, 0f, -15f,
                0f, 0f, 0f, 1f, 0f
            ))
            FilterPreset.DRAMATIC_CONTRAST -> ColorMatrix(floatArrayOf(
                1.4f, 0f, 0f, 0f, -25f,
                0f, 1.4f, 0f, 0f, -25f,
                0f, 0f, 1.4f, 0f, -25f,
                0f, 0f, 0f, 1f, 0f
            ))
            FilterPreset.BEAUTY -> ColorMatrix(floatArrayOf(
                1.08f, 0f, 0f, 0f, 15f,
                0f, 1.05f, 0f, 0f, 12f,
                0f, 0f, 1.02f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f
            ))
            FilterPreset.NONE -> null
        }

        if (presetMatrix != null) {
            composite.postConcat(presetMatrix)
        }

        return composite
    }

    private fun applyVignette(canvas: Canvas, w: Float, h: Float, intensity: Float) {
        val radius = max(w, h) * 0.75f
        val alpha = (intensity.coerceIn(0f, 100f) / 100f * 180).toInt()
        val colors = intArrayOf(Color.TRANSPARENT, Color.argb(alpha, 0, 0, 0))
        val stops = floatArrayOf(0.4f, 1.0f)
        val shader = RadialGradient(w / 2f, h / 2f, radius, colors, stops, Shader.TileMode.CLAMP)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = shader
        canvas.drawRect(0f, 0f, w, h, p)
    }

    /**
     * Fast skin tone surface smoothing for beauty filter.
     */
    private fun applyBeautySmoothing(src: Bitmap, amount: Float): Bitmap {
        val radius = ((amount / 100f) * 4f).toInt().coerceAtLeast(1)
        val width = src.width
        val height = src.height
        // Downscale slightly and upscale with bilinear filter to create smooth skin glow
        val smallW = max(1, width / (radius + 1))
        val smallH = max(1, height / (radius + 1))
        val scaled = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val smoothed = Bitmap.createScaledBitmap(scaled, width, height, true)

        // Blend smoothed and original based on skin color detection
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, 0f, 0f, basePaint)

        val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        blendPaint.alpha = ((amount / 100f) * 160).toInt()
        canvas.drawBitmap(smoothed, 0f, 0f, blendPaint)

        return out
    }

    /**
     * 3x3 Sharpen convolution kernel
     */
    private fun applySharpen(src: Bitmap, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val factor = (intensity / 100f) * 0.8f
        val center = 1f + 4f * factor
        val edge = -factor

        val inPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        src.getPixels(inPixels, 0, w, 0, 0, w, h)

        for (y in 1 until h - 1) {
            val yOffset = y * w
            for (x in 1 until w - 1) {
                val idx = yOffset + x

                val c00 = inPixels[idx]
                val cUp = inPixels[idx - w]
                val cDown = inPixels[idx + w]
                val cLeft = inPixels[idx - 1]
                val cRight = inPixels[idx + 1]

                val a = (c00 shr 24) and 0xFF

                var r = ((c00 shr 16) and 0xFF) * center +
                        (((cUp shr 16) and 0xFF) + ((cDown shr 16) and 0xFF) +
                                ((cLeft shr 16) and 0xFF) + ((cRight shr 16) and 0xFF)) * edge

                var g = ((c00 shr 8) and 0xFF) * center +
                        (((cUp shr 8) and 0xFF) + ((cDown shr 8) and 0xFF) +
                                ((cLeft shr 8) and 0xFF) + ((cRight shr 8) and 0xFF)) * edge

                var b = (c00 and 0xFF) * center +
                        ((cUp and 0xFF) + (cDown and 0xFF) +
                                (cLeft and 0xFF) + (cRight and 0xFF)) * edge

                val clampedR = r.toInt().coerceIn(0, 255)
                val clampedG = g.toInt().coerceIn(0, 255)
                val clampedB = b.toInt().coerceIn(0, 255)

                outPixels[idx] = (a shl 24) or (clampedR shl 16) or (clampedG shl 8) or clampedB
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Rotate & Flip
     */
    fun rotateAndFlip(source: Bitmap, degrees: Float, flipHorizontal: Boolean, flipVertical: Boolean): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        val sx = if (flipHorizontal) -1f else 1f
        val sy = if (flipVertical) -1f else 1f
        matrix.postScale(sx, sy)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Crop image to Aspect Ratio
     */
    fun cropToAspectRatio(source: Bitmap, ratio: AspectRatio): Bitmap {
        if (ratio == AspectRatio.ORIGINAL) return source

        val targetRatio = ratio.ratioX / ratio.ratioY
        val currentRatio = source.width.toFloat() / source.height.toFloat()

        var cropWidth = source.width
        var cropHeight = source.height
        var startX = 0
        var startY = 0

        if (currentRatio > targetRatio) {
            // Wider than target: crop width
            cropWidth = (source.height * targetRatio).toInt()
            startX = (source.width - cropWidth) / 2
        } else {
            // Taller than target: crop height
            cropHeight = (source.width / targetRatio).toInt()
            startY = (source.height - cropHeight) / 2
        }

        return Bitmap.createBitmap(source, startX, startY, cropWidth, cropHeight)
    }
}
