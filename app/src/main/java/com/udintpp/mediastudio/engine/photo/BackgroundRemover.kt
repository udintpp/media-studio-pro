package com.udintpp.mediastudio.engine.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.sqrt

object BackgroundRemover {

    /**
     * Removes background by sampling corners or dominant edges and applying a smooth threshold mask.
     * @param source Source bitmap (RGB/ARGB)
     * @param tolerance Color difference threshold (10 to 120, default 45)
     * @param targetKeyColor Optional specific color to remove (if null, auto-detects from edges)
     * @param featherRadius Feathering radius for smooth edges
     */
    fun removeBackground(
        source: Bitmap,
        tolerance: Float = 45f,
        targetKeyColor: Int? = null,
        featherRadius: Int = 2
    ): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Determine background key color
        val keyColor = targetKeyColor ?: detectDominantEdgeColor(pixels, width, height)
        val kr = Color.red(keyColor)
        val kg = Color.green(keyColor)
        val kb = Color.blue(keyColor)

        val tolSq = tolerance * tolerance
        val smoothZone = tolerance * 0.4f
        val smoothSq = (tolerance + smoothZone) * (tolerance + smoothZone)

        val outPixels = IntArray(width * height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            // Euclidean color distance squared
            val dr = r - kr
            val dg = g - kg
            val db = b - kb
            val distSq = (dr * dr + dg * dg + db * db).toFloat()

            when {
                distSq < tolSq -> {
                    // Fully transparent
                    outPixels[i] = 0x00000000
                }
                distSq < smoothSq -> {
                    // Feathered transition
                    val alphaRatio = (sqrt(distSq) - tolerance) / smoothZone
                    val alpha = (alphaRatio.coerceIn(0f, 1f) * 255).toInt()
                    outPixels[i] = (alpha shl 24) or (c and 0x00FFFFFF)
                }
                else -> {
                    // Keep original pixel
                    outPixels[i] = c
                }
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Samples four corners and edge boundaries to estimate dominant background color.
     */
    private fun detectDominantEdgeColor(pixels: IntArray, width: Int, height: Int): Int {
        val samples = mutableListOf<Int>()
        // 4 corners
        samples.add(pixels[0])
        samples.add(pixels[width - 1])
        samples.add(pixels[(height - 1) * width])
        samples.add(pixels[height * width - 1])

        // Top, bottom, left, right edge centers
        samples.add(pixels[width / 2])
        samples.add(pixels[(height - 1) * width + width / 2])
        samples.add(pixels[(height / 2) * width])
        samples.add(pixels[(height / 2) * width + width - 1])

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        for (c in samples) {
            totalR += Color.red(c)
            totalG += Color.green(c)
            totalB += Color.blue(c)
        }
        val count = samples.size
        return Color.rgb((totalR / count).toInt(), (totalG / count).toInt(), (totalB / count).toInt())
    }

    /**
     * Composite transparent foreground cutout over a new background.
     */
    fun replaceBackgroundWithColor(foreground: Bitmap, backgroundColor: Int): Bitmap {
        val result = Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(backgroundColor)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(foreground, 0f, 0f, paint)
        return result
    }

    fun replaceBackgroundWithGradient(
        foreground: Bitmap,
        colorStart: Int,
        colorEnd: Int
    ): Bitmap {
        val width = foreground.width
        val height = foreground.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), colorStart, colorEnd, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(foreground, 0f, 0f, fgPaint)
        return result
    }

    fun replaceBackgroundWithImage(foreground: Bitmap, bgImage: Bitmap): Bitmap {
        val width = foreground.width
        val height = foreground.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Scale bgImage to fit canvas
        val scaledBg = Bitmap.createScaledBitmap(bgImage, width, height, true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaledBg, 0f, 0f, paint)
        canvas.drawBitmap(foreground, 0f, 0f, paint)
        return result
    }
}
