package com.udintpp.mediastudio.engine.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.data.model.VideoExportFormat
import com.udintpp.mediastudio.engine.photo.ImageFormatEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class VideoBgReplacementType {
    TRANSPARENT, // Transparan murni (Papan catur)
    PHOTO,       // Ganti dengan foto galeri
    VIDEO,       // Ganti dengan video galeri
    SOLID_COLOR  // Ganti dengan warna solid (putih, hitam, studio, neon)
}

object VideoChromaKeyEngine {

    /**
     * Applies chroma key color removal to a single bitmap frame.
     * If replacementType is TRANSPARENT, the key color is converted to ARGB 0 (alpha = 0).
     * If replacementType is PHOTO/SOLID_COLOR/VIDEO, the foreground is blended over the replacement background.
     */
    fun processFrame(
        frame: Bitmap,
        keyColor: Int,
        tolerance: Float = 0.35f,
        feather: Float = 0.08f,
        replacementType: VideoBgReplacementType = VideoBgReplacementType.TRANSPARENT,
        replacementBgBitmap: Bitmap? = null,
        replacementBgColor: Int? = null,
        drawCheckerboardIfTransparent: Boolean = false
    ): Bitmap {
        val w = frame.width
        val h = frame.height

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)

        val keyA = (keyColor shr 24) and 0xFF
        val keyR = (keyColor shr 16) and 0xFF
        val keyG = (keyColor shr 8) and 0xFF
        val keyB = keyColor and 0xFF

        val tolSq = (tolerance * 441.67f) * (tolerance * 441.67f)
        val featherSq = ((tolerance + feather) * 441.67f) * ((tolerance + feather) * 441.67f)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            val dr = r - keyR
            val dg = g - keyG
            val db = b - keyB
            val distSq = (dr * dr + dg * dg + db * db).toFloat()

            if (distSq <= tolSq) {
                // Fully transparent background pixel
                pixels[i] = 0x00000000
            } else if (distSq < featherSq) {
                // Smooth edge feathering
                val alphaRatio = (distSq - tolSq) / (featherSq - tolSq)
                val alpha = (alphaRatio * 255).toInt().coerceIn(0, 255)
                pixels[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h)

        // Composite over background if needed
        if (replacementType != VideoBgReplacementType.TRANSPARENT || drawCheckerboardIfTransparent) {
            val composited = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(composited)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            when {
                replacementType == VideoBgReplacementType.SOLID_COLOR && replacementBgColor != null -> {
                    canvas.drawColor(replacementBgColor)
                }
                replacementType == VideoBgReplacementType.PHOTO && replacementBgBitmap != null -> {
                    val src = Rect(0, 0, replacementBgBitmap.width, replacementBgBitmap.height)
                    val dst = Rect(0, 0, w, h)
                    canvas.drawBitmap(replacementBgBitmap, src, dst, paint)
                }
                replacementType == VideoBgReplacementType.VIDEO && replacementBgBitmap != null -> {
                    val src = Rect(0, 0, replacementBgBitmap.width, replacementBgBitmap.height)
                    val dst = Rect(0, 0, w, h)
                    canvas.drawBitmap(replacementBgBitmap, src, dst, paint)
                }
                drawCheckerboardIfTransparent -> {
                    val cbShader = createCheckerboardShader(tileSize = 24)
                    val cbPaint = Paint().apply { shader = cbShader }
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), cbPaint)
                }
            }

            canvas.drawBitmap(result, 0f, 0f, paint)
            result.recycle()
            return composited
        }

        return result
    }

    private fun createCheckerboardShader(tileSize: Int): android.graphics.BitmapShader {
        val size = tileSize * 2
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p1 = Paint().apply { color = Color.parseColor("#334155") } // Dark slate gray
        val p2 = Paint().apply { color = Color.parseColor("#1E293B") } // Deep navy slate
        c.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), p1)
        c.drawRect(tileSize.toFloat(), 0f, size.toFloat(), tileSize.toFloat(), p2)
        c.drawRect(0f, tileSize.toFloat(), tileSize.toFloat(), size.toFloat(), p2)
        c.drawRect(tileSize.toFloat(), tileSize.toFloat(), size.toFloat(), size.toFloat(), p1)
        return android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    }

    /**
     * Converts a trimmed video section into an animated GIF with full alpha transparency.
     */
    suspend fun exportTransparentVideoGif(
        context: Context,
        videoUri: Uri,
        outputFile: File,
        trimStartMs: Long,
        trimEndMs: Long,
        keyColor: Int?,
        tolerance: Float,
        resolution: ExportResolution,
        fps: Int = 12,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = (trimEndMs - trimStartMs).coerceAtLeast(500L)
            val frameIntervalMs = (1000L / fps).coerceAtLeast(50L)
            val totalFrames = ((durationMs / frameIntervalMs).toInt()).coerceIn(1, 150)

            val frameBitmaps = mutableListOf<Bitmap>()
            val targetW = if (resolution == ExportResolution.ORIGINAL || resolution.width == 0) 480 else min(resolution.width, 720)

            for (i in 0 until totalFrames) {
                val currentMs = trimStartMs + (i * frameIntervalMs)
                val rawFrame = retriever.getFrameAtTime(currentMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (rawFrame != null) {
                    val scale = targetW.toFloat() / rawFrame.width.toFloat()
                    val targetH = (rawFrame.height * scale).toInt()
                    val scaled = Bitmap.createScaledBitmap(rawFrame, targetW, targetH, true)

                    val processed = if (keyColor != null) {
                        processFrame(scaled, keyColor, tolerance, replacementType = VideoBgReplacementType.TRANSPARENT)
                    } else {
                        scaled
                    }
                    frameBitmaps.add(processed)
                    onProgress((i * 60) / totalFrames)
                }
            }

            if (frameBitmaps.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Tidak ada frame video yang dapat diambil"))
            }

            // Encode Animated GIF with transparency
            val fos = FileOutputStream(outputFile)
            writeAnimatedGif(frameBitmaps, delayMs = frameIntervalMs.toInt(), out = fos)
            fos.flush()
            fos.close()

            frameBitmaps.forEach { it.recycle() }
            onProgress(100)
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            retriever.release()
        }
    }

    private fun writeAnimatedGif(frames: List<Bitmap>, delayMs: Int, out: OutputStream) {
        if (frames.isEmpty()) return
        val w = frames[0].width
        val h = frames[0].height

        // Header GIF89a
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))

        // Logical Screen Descriptor
        val lsd = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        lsd.putShort(w.toShort())
        lsd.putShort(h.toShort())
        lsd.put(0x70.toByte()) // No Global Color Table, 8 bits/pixel
        lsd.put(0) // bg index
        lsd.put(0) // aspect ratio
        out.write(lsd.array())

        // Netscape 2.0 Loop Extension
        val loopExt = byteArrayOf(
            0x21.toByte(), 0xFF.toByte(), 0x0B.toByte(),
            'N'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(),
            'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte(),
            '2'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(),
            0x03.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), // Loop forever
            0x00.toByte()
        )
        out.write(loopExt)

        val delayHundredths = (delayMs / 10).coerceAtLeast(2)

        for (frame in frames) {
            writeSingleGifFrame(frame, delayHundredths, out)
        }

        // Trailer
        out.write(0x3B)
    }

    private fun writeSingleGifFrame(bitmap: Bitmap, delayHundredths: Int, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var hasTransparency = false
        val colorMap = HashMap<Int, Int>()
        val palette = ArrayList<Int>()

        // Index 0 reserved for transparent
        palette.add(0x000000)
        colorMap[0] = 0

        for (c in pixels) {
            val a = (c shr 24) and 0xFF
            if (a < 128) {
                hasTransparency = true
            } else {
                val rgb = c and 0x00FFFFFF
                if (!colorMap.containsKey(rgb) && palette.size < 256) {
                    val idx = palette.size
                    palette.add(rgb)
                    colorMap[rgb] = idx
                }
            }
        }

        while (palette.size < 256) {
            palette.add(0)
        }

        val indexedPixels = ByteArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c shr 24) and 0xFF
            if (a < 128) {
                indexedPixels[i] = 0.toByte()
            } else {
                val rgb = c and 0x00FFFFFF
                val idx = colorMap[rgb] ?: 1
                indexedPixels[i] = idx.toByte()
            }
        }

        // Graphic Control Extension (8 bytes)
        val gce = byteArrayOf(
            0x21.toByte(), 0xF9.toByte(), 0x04.toByte(),
            (if (hasTransparency) 0x09 else 0x08).toByte(), // Disposal: restore to bg, transparent flag
            (delayHundredths and 0xFF).toByte(),
            ((delayHundredths shr 8) and 0xFF).toByte(),
            0x00.toByte(), // Transparent color index = 0
            0x00.toByte()
        )
        out.write(gce)

        // Image Descriptor with Local Color Table (10 bytes)
        val id = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        id.put(0x2C.toByte())
        id.putShort(0)
        id.putShort(0)
        id.putShort(w.toShort())
        id.putShort(h.toShort())
        id.put(0x87.toByte()) // Local Color Table flag = 1, size = 256 colors
        out.write(id.array())

        // Local Color Table (768 bytes)
        val lct = ByteArray(768)
        for (i in 0 until 256) {
            val rgb = palette[i]
            lct[i * 3 + 0] = ((rgb shr 16) and 0xFF).toByte()
            lct[i * 3 + 1] = ((rgb shr 8) and 0xFF).toByte()
            lct[i * 3 + 2] = (rgb and 0xFF).toByte()
        }
        out.write(lct)

        // Image Data: LZW
        writeLzw(indexedPixels, 8, out)
    }

    private fun writeLzw(pixels: ByteArray, initCodeSize: Int, out: OutputStream) {
        out.write(initCodeSize)

        val clearCode = 1 shl initCodeSize
        val eoiCode = clearCode + 1
        var nextCode = eoiCode + 1
        var codeSize = initCodeSize + 1
        var maxCode = 1 shl codeSize

        val dict = HashMap<Long, Int>()

        var bitBuffer = 0
        var bitCount = 0
        val packet = ByteArray(256)
        var packetLen = 0

        fun flushPacket() {
            if (packetLen > 0) {
                out.write(packetLen)
                out.write(packet, 0, packetLen)
                packetLen = 0
            }
        }

        fun writeBits(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                packet[packetLen++] = (bitBuffer and 0xFF).toByte()
                bitBuffer = bitBuffer shr 8
                bitCount -= 8
                if (packetLen == 254) {
                    flushPacket()
                }
            }
        }

        writeBits(clearCode)

        var prefix: Int? = null
        for (b in pixels) {
            val suffix = b.toInt() and 0xFF
            if (prefix == null) {
                prefix = suffix
                continue
            }
            val key = ((prefix.toLong() and 0xFFFFL) shl 16) or (suffix.toLong() and 0xFFFFL)
            val code = dict[key]
            if (code != null) {
                prefix = code
            } else {
                writeBits(prefix)
                if (nextCode < 4096) {
                    dict[key] = nextCode++
                    if (nextCode > maxCode && codeSize < 12) {
                        codeSize++
                        maxCode = 1 shl codeSize
                    }
                } else {
                    writeBits(clearCode)
                    dict.clear()
                    nextCode = eoiCode + 1
                    codeSize = initCodeSize + 1
                    maxCode = 1 shl codeSize
                }
                prefix = suffix
            }
        }

        if (prefix != null) {
            writeBits(prefix)
        }
        writeBits(eoiCode)

        if (bitCount > 0) {
            packet[packetLen++] = (bitBuffer and 0xFF).toByte()
        }
        flushPacket()
        out.write(0x00)
    }
}
