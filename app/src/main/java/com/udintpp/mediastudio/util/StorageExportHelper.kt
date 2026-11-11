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
     * strictly ensuring ONLY ONE SINGLE FILE exists (NO DUPLICATES),
     * and indexes it in Android MediaStore via MediaScannerConnection.
     */
    fun syncExportedFile(context: Context, sourceFile: File, mimeType: String = "video/mp4"): File {
        val exportDir = File(PATH_EXPORT_FOLDER)
        if (!exportDir.exists()) exportDir.mkdirs()
        val targetFile = File(exportDir, sourceFile.name)

        // If sourceFile was created in temporary cacheDir, move it to DCIM/video-pro and delete temp
        if (sourceFile.absolutePath != targetFile.absolutePath) {
            try {
                copyFile(sourceFile, targetFile)
                if (targetFile.exists() && targetFile.length() > 0) {
                    sourceFile.delete() // Clean up temp file to prevent duplicate
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error moving cache file to DCIM/video-pro: ${e.message}")
            }
        }

        val finalFile = if (targetFile.exists() && targetFile.length() > 0) targetFile else sourceFile

        // Register the single exact file directly with MediaScannerConnection.
        // DO NOT use resolver.insert() here, as resolver.insert() would create a second file with " (1)" in the same directory!
        if (finalFile.exists()) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(finalFile.absolutePath),
                arrayOf(mimeType)
            ) { path, uri ->
                Log.d(TAG, "Indexed single file in MediaStore: $path -> $uri")
            }
        }

        return finalFile
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
