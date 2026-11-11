package com.udintpp.mediastudio.engine.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.data.model.PhotoExportFormat
import com.udintpp.mediastudio.data.model.StickerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhotoExporter {

    /**
     * Composites base image and all sticker/emoji overlay layers, then scales to target resolution (up to 4K UHD)
     * and encodes to user's desired format (PNG, JPG, WEBP, GIF, ICO, PSD, PCX, SVG, AVG, BMP, TIFF).
     *
     * IMPORTANT: If no replacement background (backgroundBitmap or backgroundColorOverride) is set,
     * the transparent background is preserved 100% in all transparency-capable formats!
     */
    suspend fun exportPhoto(
        context: Context,
        baseBitmap: Bitmap,
        stickers: List<StickerItem>,
        resolution: ExportResolution,
        format: PhotoExportFormat = PhotoExportFormat.PNG,
        quality: Int = 100,
        backgroundBitmap: Bitmap? = null,
        backgroundColorOverride: Int? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            // Determine output dimensions
            val (targetWidth, targetHeight) = calculateDimensions(baseBitmap.width, baseBitmap.height, resolution)

            // 1. Create full-resolution canvas with ARGB_8888 for alpha transparency
            val finalBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(finalBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // 2. Draw replacement background ONLY if explicitly set by the user
            if (backgroundColorOverride != null) {
                canvas.drawColor(backgroundColorOverride)
            } else if (backgroundBitmap != null) {
                val dstRect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
                canvas.drawBitmap(backgroundBitmap, null, dstRect, paint)
            }
            // If neither is set, canvas remains completely transparent (alpha = 0)!

            // 3. Draw scaled base bitmap
            val scaledBase = if (baseBitmap.width == targetWidth && baseBitmap.height == targetHeight) {
                baseBitmap
            } else {
                Bitmap.createScaledBitmap(baseBitmap, targetWidth, targetHeight, true)
            }
            canvas.drawBitmap(scaledBase, 0f, 0f, paint)

            // 4. Scale factor for stickers relative to preview dimensions
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

            // 5. Generate filename with selected extension
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "MediaStudio_${resolution.name}_$timeStamp.${format.extension}"

            // 6. Save encoded media
            val uri = saveToStorage(context, finalBitmap, filename, format, quality)
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

    private fun saveToStorage(
        context: Context,
        bitmap: Bitmap,
        filename: String,
        format: PhotoExportFormat,
        quality: Int
    ): Uri {
        val resolver = context.contentResolver
        val contentUri = if (format == PhotoExportFormat.AVG) {
            MediaStore.Files.getContentUri("external")
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/video-pro")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(contentUri, values)
            ?: throw IllegalStateException("Failed to create MediaStore entry for $filename")

        resolver.openOutputStream(uri)?.use { out ->
            ImageFormatEncoder.encode(bitmap, format, quality, out)
            out.flush()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return uri
    }
}
