package com.udintpp.mediastudio.engine.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object BackgroundRemover {

    /**
     * AI-Powered Background Removal using Google ML Kit Selfie Segmentation neural network.
     * Accurately distinguishes humans (face, hair, body, clothes, arms, legs) from any background.
     */
    suspend fun removeBackgroundAI(
        source: Bitmap,
        confidenceThreshold: Float = 0.5f
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()

            val segmenter = Segmentation.getClient(options)
            val inputImage = InputImage.fromBitmap(source, 0)

            val mask = suspendCancellableCoroutine { cont ->
                segmenter.process(inputImage)
                    .addOnSuccessListener { segMask ->
                        cont.resume(segMask)
                    }
                    .addOnFailureListener { exc ->
                        cont.resumeWithException(exc)
                    }
            }

            val maskBuffer = mask.buffer
            val maskWidth = mask.width
            val maskHeight = mask.height

            val width = source.width
            val height = source.height

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val srcPixels = IntArray(width * height)
            source.getPixels(srcPixels, 0, width, 0, 0, width, height)

            val outPixels = IntArray(width * height)

            // Extract float confidences into 2D float array
            val confidences = FloatArray(maskWidth * maskHeight)
            maskBuffer.rewind()
            for (i in confidences.indices) {
                confidences[i] = maskBuffer.float
            }

            val scaleX = maskWidth.toFloat() / width.toFloat()
            val scaleY = maskHeight.toFloat() / height.toFloat()

            for (y in 0 until height) {
                val my = (y * scaleY).toInt().coerceIn(0, maskHeight - 1)
                val rowOffset = y * width
                val maskRowOffset = my * maskWidth

                for (x in 0 until width) {
                    val mx = (x * scaleX).toInt().coerceIn(0, maskWidth - 1)
                    val conf = confidences[maskRowOffset + mx]
                    val pixel = srcPixels[rowOffset + x]

                    val alpha = when {
                        conf >= confidenceThreshold + 0.15f -> 255
                        conf <= confidenceThreshold - 0.15f -> 0
                        else -> {
                            // Soft edge feathering between foreground and background
                            val ratio = (conf - (confidenceThreshold - 0.15f)) / 0.30f
                            (ratio.coerceIn(0f, 1f) * 255).toInt()
                        }
                    }

                    outPixels[rowOffset + x] = (alpha shl 24) or (pixel and 0x00FFFFFF)
                }
            }

            output.setPixels(outPixels, 0, width, 0, 0, width, height)
            segmenter.close()
            Result.success(output)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Smart Boundary-Connected Flood Fill with Human Skin Tone Protection & Hole Filling.
     * Prevents deleting parts of the person's body or clothes even if colors match the background!
     */
    fun removeBackgroundSmartColor(
        source: Bitmap,
        tolerance: Float = 35f,
        targetKeyColor: Int? = null
    ): Bitmap {
        val width = source.width
        val height = source.height
        val totalPixels = width * height

        val srcPixels = IntArray(totalPixels)
        source.getPixels(srcPixels, 0, width, 0, 0, width, height)

        val keyColor = targetKeyColor ?: detectDominantEdgeColor(srcPixels, width, height)
        val kr = Color.red(keyColor)
        val kg = Color.green(keyColor)
        val kb = Color.blue(keyColor)

        val tolSq = tolerance * tolerance
        val smoothZone = tolerance * 0.4f
        val smoothSq = (tolerance + smoothZone) * (tolerance + smoothZone)

        // BFS Flood fill from outer image borders only
        // 0 = unvisited foreground, 1 = background, 2 = border edge transition
        val visited = ByteArray(totalPixels)
        val queue = ArrayDeque<Int>()

        // Add 4 outer borders to queue
        for (x in 0 until width) {
            queue.add(x) // Top row
            queue.add((height - 1) * width + x) // Bottom row
        }
        for (y in 1 until height - 1) {
            queue.add(y * width) // Left col
            queue.add(y * width + width - 1) // Right col
        }

        while (queue.isNotEmpty()) {
            val idx = queue.poll()
            if (visited[idx] != 0.toByte()) continue

            val c = srcPixels[idx]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            // PROTECT SKIN TONES: If pixel is human skin, NEVER classify as background!
            if (isHumanSkinTone(r, g, b)) {
                visited[idx] = 3 // Protected human skin
                continue
            }

            val dr = r - kr
            val dg = g - kg
            val db = b - kb
            val distSq = (dr * dr + dg * dg + db * db).toFloat()

            if (distSq < tolSq) {
                visited[idx] = 1 // Confirmed background
                val x = idx % width
                val y = idx / width

                // Expand to 4-way neighbors
                if (x > 0 && visited[idx - 1] == 0.toByte()) queue.add(idx - 1)
                if (x < width - 1 && visited[idx + 1] == 0.toByte()) queue.add(idx + 1)
                if (y > 0 && visited[idx - width] == 0.toByte()) queue.add(idx - width)
                if (y < height - 1 && visited[idx + width] == 0.toByte()) queue.add(idx + width)
            } else if (distSq < smoothSq) {
                visited[idx] = 2 // Transition edge
            }
        }

        val outPixels = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            val c = srcPixels[i]
            when (visited[i]) {
                1.toByte() -> {
                    outPixels[i] = 0x00000000 // Fully transparent
                }
                2.toByte() -> {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val dr = r - kr
                    val dg = g - kg
                    val db = b - kb
                    val distSq = (dr * dr + dg * dg + db * db).toFloat()
                    val ratio = (sqrt(distSq) - tolerance) / smoothZone
                    val alpha = (ratio.coerceIn(0f, 1f) * 255).toInt()
                    outPixels[i] = (alpha shl 24) or (c and 0x00FFFFFF)
                }
                else -> {
                    // Protected foreground (person, clothes, interior)
                    outPixels[i] = c
                }
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Checks if RGB values fall into human skin color range (YCbCr color space standard).
     * Prevents accidental deletion of face, hands, neck, body skin!
     */
    private fun isHumanSkinTone(r: Int, g: Int, b: Int): Boolean {
        // Convert RGB to YCbCr
        val y = 0.299 * r + 0.587 * g + 0.114 * b
        val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b

        return (cb in 77.0..127.0 && cr in 133.0..173.0 && y > 40.0)
    }

    /**
     * Manual touch brush to restore deleted parts from original image.
     */
    fun restoreBrush(
        cutout: Bitmap,
        original: Bitmap,
        touchX: Float,
        touchY: Float,
        brushRadius: Float = 40f
    ) {
        val width = cutout.width
        val height = cutout.height
        val radSq = brushRadius * brushRadius

        val minX = max(0, (touchX - brushRadius).toInt())
        val maxX = min(width - 1, (touchX + brushRadius).toInt())
        val minY = max(0, (touchY - brushRadius).toInt())
        val maxY = min(height - 1, (touchY + brushRadius).toInt())

        for (y in minY..maxY) {
            val dy = y - touchY
            for (x in minX..maxX) {
                val dx = x - touchX
                if (dx * dx + dy * dy <= radSq) {
                    val origPixel = original.getPixel(x, y)
                    cutout.setPixel(x, y, origPixel)
                }
            }
        }
    }

    /**
     * Manual eraser brush to erase unwanted background remnants.
     */
    fun eraseBrush(
        cutout: Bitmap,
        touchX: Float,
        touchY: Float,
        brushRadius: Float = 40f
    ) {
        val width = cutout.width
        val height = cutout.height
        val radSq = brushRadius * brushRadius

        val minX = max(0, (touchX - brushRadius).toInt())
        val maxX = min(width - 1, (touchX + brushRadius).toInt())
        val minY = max(0, (touchY - brushRadius).toInt())
        val maxY = min(height - 1, (touchY + brushRadius).toInt())

        for (y in minY..maxY) {
            val dy = y - touchY
            for (x in minX..maxX) {
                val dx = x - touchX
                if (dx * dx + dy * dy <= radSq) {
                    cutout.setPixel(x, y, 0x00000000)
                }
            }
        }
    }

    private fun detectDominantEdgeColor(pixels: IntArray, width: Int, height: Int): Int {
        val samples = mutableListOf<Int>()
        samples.add(pixels[0])
        samples.add(pixels[width - 1])
        samples.add(pixels[(height - 1) * width])
        samples.add(pixels[height * width - 1])

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

        val scaledBg = Bitmap.createScaledBitmap(bgImage, width, height, true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaledBg, 0f, 0f, paint)
        canvas.drawBitmap(foreground, 0f, 0f, paint)
        return result
    }
}
