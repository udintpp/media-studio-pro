package com.udintpp.mediastudio.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Storage export helper ensuring videos and media are saved directly to public shared storage:
 * - /storage/emulated/0/DCIM/video-pro/
 * and properly registered with Android MediaStore / MediaScanner for instant Gallery visibility.
 */
object StorageExportHelper {

    private const val TAG = "StorageExportHelper"
    const val PATH_EXPORT_FOLDER = "/storage/emulated/0/DCIM/video-pro"

    /**
     * Prepares primary output file for video export in DCIM/video-pro.
     */
    fun createVideoOutputFile(context: Context, prefix: String, extension: String): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${prefix}_$timeStamp.$extension"

        // Ensure DCIM/video-pro directory exists
        val exportDir = File(PATH_EXPORT_FOLDER)
        if (!exportDir.exists()) exportDir.mkdirs()

        // Test if DCIM/video-pro is directly writable
        val targetFile = File(exportDir, fileName)
        return try {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (targetFile.createNewFile() || targetFile.canWrite()) {
                targetFile
            } else {
                File(context.cacheDir, fileName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct storage write blocked initially, using cache temp: ${e.message}")
            File(context.cacheDir, fileName)
        }
    }

    /**
     * Syncs finished video or media file to /storage/emulated/0/DCIM/video-pro/,
     * then indexes it in the Android MediaStore.
     */
    fun syncExportedFile(context: Context, sourceFile: File, mimeType: String = "video/mp4"): File? {
        var savedFile: File? = null

        try {
            val exportDir = File(PATH_EXPORT_FOLDER)
            if (!exportDir.exists()) exportDir.mkdirs()
            val targetFile = File(exportDir, sourceFile.name)

            if (sourceFile.absolutePath != targetFile.absolutePath) {
                copyFile(sourceFile, targetFile)
            }
            if (targetFile.exists() && targetFile.length() > 0) {
                savedFile = targetFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to DCIM/video-pro: ${e.message}")
        }

        // Register to MediaStore on Android 10+ (Q+) for instant gallery visibility
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/video-pro")
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val collectionUri = if (mimeType.startsWith("image/")) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                resolver.insert(collectionUri, contentValues)?.let { uri ->
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert notice: ${e.message}")
        }

        // Trigger MediaScanner for saved path
        val fileToScan = savedFile ?: sourceFile
        if (fileToScan.exists()) {
            MediaScannerConnection.scanFile(context, arrayOf(fileToScan.absolutePath), arrayOf(mimeType)) { path, uri ->
                Log.d(TAG, "Scanned $path -> $uri")
            }
        }

        return savedFile
    }

    private fun copyFile(src: File, dst: File) {
        if (!src.exists()) return
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
    }

    fun getSuccessNotificationMessage(fileName: String): String {
        return "📁 Tersimpan di Storage:\n• /DCIM/video-pro/$fileName\n(Otomatis tampil di Galeri)"
    }
}
