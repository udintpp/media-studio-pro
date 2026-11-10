package com.udintpp.mediastudio.engine.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoFrameExtractor {

    data class ExtractedFrame(
        val timestampMs: Long,
        val bitmap: Bitmap,
        var savedUri: Uri? = null
    )

    /**
     * Extracts a single frame at exact timestamp in milliseconds.
     */
    suspend fun extractFrameAt(
        context: Context,
        videoUri: Uri,
        timeMs: Long
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val timeUs = timeMs * 1000L

            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, -1, -1)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }

            if (bitmap != null) {
                Result.success(bitmap)
            } else {
                Result.failure(IllegalStateException("Tidak dapat mengambil frame pada waktu ${timeMs}ms"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Extracts frames periodically across the video duration (e.g. every intervalSec).
     */
    suspend fun extractPeriodicFrames(
        context: Context,
        videoUri: Uri,
        intervalMs: Long = 1000L,
        maxFrames: Int = 30,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<List<ExtractedFrame>> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val results = mutableListOf<ExtractedFrame>()
        try {
            retriever.setDataSource(context, videoUri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val totalDurationMs = durationStr?.toLongOrNull() ?: 0L

            if (totalDurationMs <= 0L) {
                return@withContext Result.failure(IllegalStateException("Durasi video tidak valid"))
            }

            var currentMs = 0L
            var count = 0

            while (currentMs < totalDurationMs && count < maxFrames) {
                val timeUs = currentMs * 1000L
                val frameBmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frameBmp != null) {
                    results.add(ExtractedFrame(currentMs, frameBmp))
                }
                count++
                onProgress(count, maxFrames)
                currentMs += intervalMs
            }

            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Saves extracted frame to Gallery (Pictures/MediaStudioPro/Frames)
     */
    suspend fun saveFrameToGallery(
        context: Context,
        bitmap: Bitmap,
        timestampMs: Long
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "Frame_${timestampMs}ms_$timeStamp.png"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MediaStudioPro/Frames")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Gagal membuat entri penyimpanan")

            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
