package com.udintpp.mediastudio.engine.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.data.model.StickerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhotoExporter {

    /**
     * Composites base image and all sticker/emoji overlay layers, then scales to target resolution (up to 4K UHD).
     */
    suspend fun exportPhoto(
        context: Context,
        baseBitmap: Bitmap,
        stickers: List<StickerItem>,
        resolution: ExportResolution,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            // Determine output dimensions
            val (targetWidth, targetHeight) = calculateDimensions(baseBitmap.width, baseBitmap.height, resolution)

            // 1. Create full-resolution canvas
            val finalBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(finalBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // Draw scaled base bitmap
            val scaledBase = if (baseBitmap.width == targetWidth && baseBitmap.height == targetHeight) {
                baseBitmap
            } else {
                Bitmap.createScaledBitmap(baseBitmap, targetWidth, targetHeight, true)
            }
            canvas.drawBitmap(scaledBase, 0f, 0f, paint)

            // Scale factor for stickers relative to preview dimensions
            val scaleX = targetWidth.toFloat() / baseBitmap.width.toFloat()
            val scaleY = targetHeight.toFloat() / baseBitmap.height.toFloat()

            for (sticker in stickers) {
                val stickerBmp = sticker.bitmap ?: continue
                canvas.save()
                canvas.translate(sticker.x * scaleX, sticker.y * scaleY)
                canvas.rotate(sticker.rotation)
                val sFactor = sticker.scale * scaleX
                canvas.scale(sFactor, sFactor)

                val stPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (sticker.alpha * 255).toInt()
                }
                canvas.drawBitmap(
                    stickerBmp,
                    -stickerBmp.width / 2f,
                    -stickerBmp.height / 2f,
                    stPaint
                )
                canvas.restore()
            }

            // Save to MediaStore Pictures/MediaStudioPro
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "MediaStudio_4K_$timeStamp.${if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"}"

            val uri = saveToMediaStore(context, finalBitmap, filename, format, quality)
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun calculateDimensions(origW: Int, origH: Int, resolution: ExportResolution): Pair<Int, Int> {
        if (resolution == ExportResolution.ORIGINAL || resolution.width == 0) {
            return Pair(origW, origH)
        }

        val targetMaxW = resolution.width
        val targetMaxH = resolution.height
        val aspectRatio = origW.toFloat() / origH.toFloat()

        return if (origW >= origH) {
            // Landscape
            val w = targetMaxW
            val h = (targetMaxW / aspectRatio).toInt()
            Pair(w, h)
        } else {
            // Portrait
            val h = targetMaxW
            val w = (targetMaxW * aspectRatio).toInt()
            Pair(w, h)
        }
    }

    private fun saveToMediaStore(
        context: Context,
        bitmap: Bitmap,
        filename: String,
        format: Bitmap.CompressFormat,
        quality: Int
    ): Uri {
        val resolver = context.contentResolver
        val mimeType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MediaStudioPro")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(format, quality, out)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return uri
    }
}
