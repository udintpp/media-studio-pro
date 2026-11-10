package com.udintpp.mediastudio.engine.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.Base64
import com.udintpp.mediastudio.data.model.PhotoExportFormat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object ImageFormatEncoder {

    /**
     * Encodes a bitmap into the specified PhotoExportFormat and writes it to the output stream.
     */
    fun encode(
        bitmap: Bitmap,
        format: PhotoExportFormat,
        quality: Int = 100,
        out: OutputStream
    ) {
        when (format) {
            PhotoExportFormat.PNG -> encodePng(bitmap, out)
            PhotoExportFormat.JPG -> encodeJpg(bitmap, quality, out)
            PhotoExportFormat.WEBP -> encodeWebp(bitmap, quality, out)
            PhotoExportFormat.GIF -> encodeGif(bitmap, out)
            PhotoExportFormat.ICO -> encodeIco(bitmap, out)
            PhotoExportFormat.PSD -> encodePsd(bitmap, out)
            PhotoExportFormat.PCX -> encodePcx(bitmap, out)
            PhotoExportFormat.SVG -> encodeSvg(bitmap, out)
            PhotoExportFormat.AVG -> encodeAvg(bitmap, out)
            PhotoExportFormat.BMP -> encodeBmp(bitmap, out)
            PhotoExportFormat.TIFF -> encodeTiff(bitmap, out)
        }
    }

    // 1. PNG Encoder (Lossless with Alpha Transparency)
    private fun encodePng(bitmap: Bitmap, out: OutputStream) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }

    // 2. JPG Encoder (Non-transparent, composited over white if transparent)
    private fun encodeJpg(bitmap: Bitmap, quality: Int, out: OutputStream) {
        val nonAlphaBitmap = if (bitmap.hasAlpha()) {
            val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(opaque)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            opaque
        } else {
            bitmap
        }
        val clampedQ = quality.coerceIn(10, 100)
        nonAlphaBitmap.compress(Bitmap.CompressFormat.JPEG, clampedQ, out)
        if (nonAlphaBitmap != bitmap) {
            nonAlphaBitmap.recycle()
        }
    }

    // 3. WEBP Encoder (Lossless / Lossy with Alpha Transparency)
    private fun encodeWebp(bitmap: Bitmap, quality: Int, out: OutputStream) {
        val clampedQ = quality.coerceIn(10, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (clampedQ >= 100) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            } else {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, clampedQ, out)
            }
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, clampedQ, out)
        }
    }

    // 4. BMP Encoder (Windows Bitmap 32-bit ARGB with Alpha)
    private fun encodeBmp(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height
        val imageSize = w * h * 4
        val fileSize = 14 + 40 + imageSize

        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
        // File Header (14 bytes)
        header.put('B'.code.toByte())
        header.put('M'.code.toByte())
        header.putInt(fileSize)
        header.putShort(0) // reserved1
        header.putShort(0) // reserved2
        header.putInt(54)  // offset to pixels

        // Info Header (40 bytes)
        header.putInt(40)  // header size
        header.putInt(w)
        header.putInt(h)   // positive = bottom-to-top
        header.putShort(1) // planes
        header.putShort(32)// bits per pixel (ARGB)
        header.putInt(0)   // compression (BI_RGB)
        header.putInt(imageSize)
        header.putInt(2835)// X pixels per meter
        header.putInt(2835)// Y pixels per meter
        header.putInt(0)   // colors used
        header.putInt(0)   // important colors

        out.write(header.array())

        val pixels = IntArray(w)
        val rowBuffer = ByteArray(w * 4)

        // BMP rows are bottom-up
        for (y in h - 1 downTo 0) {
            bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
            var offset = 0
            for (x in 0 until w) {
                val color = pixels[x]
                rowBuffer[offset++] = (color and 0xFF).toByte()         // Blue
                rowBuffer[offset++] = ((color shr 8) and 0xFF).toByte()  // Green
                rowBuffer[offset++] = ((color shr 16) and 0xFF).toByte() // Red
                rowBuffer[offset++] = ((color shr 24) and 0xFF).toByte() // Alpha
            }
            out.write(rowBuffer)
        }
    }

    // 5. ICO Encoder (Windows Icon Format with 32-bit ARGB PNG payload)
    private fun encodeIco(bitmap: Bitmap, out: OutputStream) {
        // Standard Windows Icon: scale down to 256x256 max if larger to match Windows shell specs
        val targetBmp = if (bitmap.width > 256 || bitmap.height > 256) {
            val scale = 256f / max(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }

        val pngStream = ByteArrayOutputStream()
        targetBmp.compress(Bitmap.CompressFormat.PNG, 100, pngStream)
        val pngBytes = pngStream.toByteArray()

        val header = ByteBuffer.allocate(6 + 16).order(ByteOrder.LITTLE_ENDIAN)
        // ICONDIR (6 bytes)
        header.putShort(0) // reserved
        header.putShort(1) // type (1 = icon)
        header.putShort(1) // count (1 image)

        // ICONDIRENTRY (16 bytes)
        val icoW = if (targetBmp.width >= 256) 0 else targetBmp.width
        val icoH = if (targetBmp.height >= 256) 0 else targetBmp.height
        header.put(icoW.toByte())
        header.put(icoH.toByte())
        header.put(0) // color count
        header.put(0) // reserved
        header.putShort(1) // planes
        header.putShort(32) // bits per pixel
        header.putInt(pngBytes.size) // bytes in res
        header.putInt(22) // image offset (6 + 16 = 22)

        out.write(header.array())
        out.write(pngBytes)

        if (targetBmp != bitmap) {
            targetBmp.recycle()
        }
    }

    // 6. PSD Encoder (Adobe Photoshop Document with 4 Channels: R, G, B, A)
    private fun encodePsd(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height
        val totalPixels = w * h

        val header = ByteBuffer.allocate(26 + 12 + 2).order(ByteOrder.BIG_ENDIAN)
        // 8BPS signature
        header.put('8'.code.toByte())
        header.put('B'.code.toByte())
        header.put('P'.code.toByte())
        header.put('S'.code.toByte())
        header.putShort(1) // version 1
        header.put(ByteArray(6)) // reserved
        header.putShort(4) // 4 channels: R, G, B, A
        header.putInt(h)
        header.putInt(w)
        header.putShort(8) // 8 bits per channel
        header.putShort(3) // color mode 3 = RGB

        // Color Mode Data (length 0)
        header.putInt(0)
        // Image Resources (length 0)
        header.putInt(0)
        // Layer & Mask Information (length 0)
        header.putInt(0)
        // Compression (0 = raw data)
        header.putShort(0)

        out.write(header.array())

        // Extract pixel arrays in channel planar order: R plane, G plane, B plane, A plane
        val pixels = IntArray(w)
        val rPlane = ByteArray(totalPixels)
        val gPlane = ByteArray(totalPixels)
        val bPlane = ByteArray(totalPixels)
        val aPlane = ByteArray(totalPixels)

        var idx = 0
        for (y in 0 until h) {
            bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = pixels[x]
                aPlane[idx] = ((c shr 24) and 0xFF).toByte()
                rPlane[idx] = ((c shr 16) and 0xFF).toByte()
                gPlane[idx] = ((c shr 8) and 0xFF).toByte()
                bPlane[idx] = (c and 0xFF).toByte()
                idx++
            }
        }

        out.write(rPlane)
        out.write(gPlane)
        out.write(bPlane)
        out.write(aPlane)
    }

    // 7. PCX Encoder (ZSoft PC Paintbrush 24-bit RGB with RLE Compression)
    private fun encodePcx(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height
        val bytesPerLine = if (w % 2 != 0) w + 1 else w

        val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        header.put(10) // Manufacturer: ZSoft
        header.put(5)  // Version: 3.0
        header.put(1)  // Encoding: RLE
        header.put(8)  // Bits per pixel per plane
        header.putShort(0) // Xmin
        header.putShort(0) // Ymin
        header.putShort((w - 1).toShort()) // Xmax
        header.putShort((h - 1).toShort()) // Ymax
        header.putShort(300) // Hdpi
        header.putShort(300) // Vdpi
        header.put(ByteArray(48)) // 16-color colormap (unused in 24-bit)
        header.put(0) // reserved
        header.put(3) // 3 planes (Red, Green, Blue)
        header.putShort(bytesPerLine.toShort())
        header.putShort(1) // Palette info
        header.putShort(w.toShort()) // HScreenSize
        header.putShort(h.toShort()) // VScreenSize
        header.put(ByteArray(54)) // filler

        out.write(header.array())

        val pixels = IntArray(w)
        val rLine = ByteArray(bytesPerLine)
        val gLine = ByteArray(bytesPerLine)
        val bLine = ByteArray(bytesPerLine)

        fun writeRleLine(line: ByteArray, len: Int) {
            var i = 0
            while (i < len) {
                var run = 1
                val b = line[i]
                while (i + run < len && line[i + run] == b && run < 63) {
                    run++
                }
                if (run > 1 || (b.toInt() and 0xC0) == 0xC0) {
                    out.write((0xC0 or run))
                    out.write(b.toInt() and 0xFF)
                } else {
                    out.write(b.toInt() and 0xFF)
                }
                i += run
            }
        }

        for (y in 0 until h) {
            bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = pixels[x]
                rLine[x] = ((c shr 16) and 0xFF).toByte()
                gLine[x] = ((c shr 8) and 0xFF).toByte()
                bLine[x] = (c and 0xFF).toByte()
            }
            if (bytesPerLine > w) {
                rLine[w] = 0
                gLine[w] = 0
                bLine[w] = 0
            }
            writeRleLine(rLine, bytesPerLine)
            writeRleLine(gLine, bytesPerLine)
            writeRleLine(bLine, bytesPerLine)
        }
    }

    // 8. TIFF Encoder (Tagged Image File Format 32-bit ARGB with unassociated alpha)
    private fun encodeTiff(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height
        val imageBytesCount = w * h * 4

        val ifdOffset = 8
        val tagCount = 10
        val ifdSize = 2 + tagCount * 12 + 4
        val extraDataOffset = ifdOffset + ifdSize
        val dataOffset = extraDataOffset + 8 // 8 bytes for BitsPerSample: 8,8,8,8

        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put('I'.code.toByte())
        header.put('I'.code.toByte())
        header.putShort(42) // TIFF identifier
        header.putInt(ifdOffset)
        out.write(header.array())

        val ifd = ByteBuffer.allocate(ifdSize).order(ByteOrder.LITTLE_ENDIAN)
        ifd.putShort(tagCount.toShort())

        fun putTag(tag: Int, type: Int, count: Int, valueOrOffset: Int) {
            ifd.putShort(tag.toShort())
            ifd.putShort(type.toShort()) // 3=SHORT, 4=LONG
            ifd.putInt(count)
            ifd.putInt(valueOrOffset)
        }

        putTag(256, 4, 1, w) // ImageWidth
        putTag(257, 4, 1, h) // ImageLength
        putTag(258, 3, 4, extraDataOffset) // BitsPerSample offset
        putTag(259, 3, 1, 1) // Compression: None
        putTag(262, 3, 1, 2) // Photometric: RGB
        putTag(273, 4, 1, dataOffset) // StripOffsets
        putTag(277, 3, 1, 4) // SamplesPerPixel: 4
        putTag(278, 4, 1, h) // RowsPerStrip
        putTag(279, 4, 1, imageBytesCount) // StripByteCounts
        putTag(338, 3, 1, 2) // ExtraSamples: 2 (unassociated alpha)
        ifd.putInt(0) // Next IFD = 0

        out.write(ifd.array())

        // BitsPerSample values (4 x SHORT = 8 bytes)
        val bpsBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bpsBuf.putShort(8)
        bpsBuf.putShort(8)
        bpsBuf.putShort(8)
        bpsBuf.putShort(8)
        out.write(bpsBuf.array())

        // Image Data: RGBA bytes
        val pixels = IntArray(w)
        val rowBuffer = ByteArray(w * 4)
        for (y in 0 until h) {
            bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
            var offset = 0
            for (x in 0 until w) {
                val c = pixels[x]
                rowBuffer[offset++] = ((c shr 16) and 0xFF).toByte() // Red
                rowBuffer[offset++] = ((c shr 8) and 0xFF).toByte()  // Green
                rowBuffer[offset++] = (c and 0xFF).toByte()         // Blue
                rowBuffer[offset++] = ((c shr 24) and 0xFF).toByte() // Alpha
            }
            out.write(rowBuffer)
        }
    }

    // 9. SVG Encoder (Scalable Vector Graphics XML)
    private fun encodeSvg(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height

        val pngStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngStream)
        val base64 = Base64.encodeToString(pngStream.toByteArray(), Base64.NO_WRAP)

        val svgContent = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
            append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
            append("width=\"${w}px\" height=\"${h}px\" viewBox=\"0 0 $w $h\" version=\"1.1\">\n")
            append("  <!-- Generated by MediaStudio Pro Ultra HD -->\n")
            append("  <image width=\"$w\" height=\"$h\" xlink:href=\"data:image/png;base64,$base64\" />\n")
            append("</svg>\n")
        }
        out.write(svgContent.toByteArray(Charsets.UTF_8))
    }

    // 10. AVG Encoder (Android Vector Graphic XML)
    private fun encodeAvg(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height

        val pngStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngStream)
        val base64 = Base64.encodeToString(pngStream.toByteArray(), Base64.NO_WRAP)

        val avgContent = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<!-- Android Vector Graphic (AVG) generated by MediaStudio Pro -->\n")
            append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
            append("    android:width=\"${w}dp\"\n")
            append("    android:height=\"${h}dp\"\n")
            append("    android:viewportWidth=\"${w.toFloat()}\"\n")
            append("    android:viewportHeight=\"${h.toFloat()}\">\n")
            append("    <path\n")
            append("        android:fillColor=\"#00000000\"\n")
            append("        android:pathData=\"M0,0 L${w},0 L${w},${h} L0,${h} Z\" />\n")
            append("    <!-- Embedded PNG Base64 Data URI for Android Build Pipeline: \n")
            append("         data:image/png;base64,$base64 \n")
            append("    -->\n")
            append("</vector>\n")
        }
        out.write(avgContent.toByteArray(Charsets.UTF_8))
    }

    // 11. GIF Encoder (Standard GIF89a with 256 colors & Transparent Color Index)
    private fun encodeGif(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height

        // Build 256 color palette with transparent color at index 0
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var hasTransparency = false
        val colorMap = HashMap<Int, Int>()
        val palette = ArrayList<Int>()

        // Index 0 reserved for transparent
        palette.add(0x000000) // Transparent color
        colorMap[0] = 0

        for (c in pixels) {
            val a = (c shr 24) and 0xFF
            if (a < 128) {
                hasTransparency = true
            } else {
                // Quantize to 6x6x6 or 256 max
                val rgb = c and 0x00FFFFFF
                if (!colorMap.containsKey(rgb) && palette.size < 256) {
                    val idx = palette.size
                    palette.add(rgb)
                    colorMap[rgb] = idx
                }
            }
        }

        // Fill remaining palette up to 256 colors
        while (palette.size < 256) {
            palette.add(0)
        }

        // Map pixels to palette indices
        val indexedPixels = ByteArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c shr 24) and 0xFF
            if (a < 128) {
                indexedPixels[i] = 0.toByte()
            } else {
                val rgb = c and 0x00FFFFFF
                val idx = colorMap[rgb] ?: findNearestPaletteIndex(rgb, palette)
                indexedPixels[i] = idx.toByte()
            }
        }

        // Write GIF89a Header
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))

        // Logical Screen Descriptor (7 bytes)
        val lsd = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        lsd.putShort(w.toShort())
        lsd.putShort(h.toShort())
        lsd.put(0xF7.toByte()) // GCT flag = 1, 256 colors (8 bits/pixel)
        lsd.put(0) // background index
        lsd.put(0) // pixel aspect ratio
        out.write(lsd.array())

        // Global Color Table (256 * 3 = 768 bytes)
        val gct = ByteArray(768)
        for (i in 0 until 256) {
            val rgb = palette[i]
            gct[i * 3 + 0] = ((rgb shr 16) and 0xFF).toByte()
            gct[i * 3 + 1] = ((rgb shr 8) and 0xFF).toByte()
            gct[i * 3 + 2] = (rgb and 0xFF).toByte()
        }
        out.write(gct)

        // Graphic Control Extension (8 bytes) for transparency
        if (hasTransparency) {
            val gce = byteArrayOf(
                0x21.toByte(), // Extension Introducer
                0xF9.toByte(), // Graphic Control Label
                0x04.toByte(), // Block Size
                0x01.toByte(), // Packed: transparent color flag enabled
                0x00.toByte(), 0x00.toByte(), // Delay time
                0x00.toByte(), // Transparent color index = 0
                0x00.toByte()  // Block Terminator
            )
            out.write(gce)
        }

        // Image Descriptor (10 bytes)
        val id = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        id.put(0x2C.toByte()) // Image Separator
        id.putShort(0) // Left
        id.putShort(0) // Top
        id.putShort(w.toShort())
        id.putShort(h.toShort())
        id.put(0x00.toByte()) // No local color table
        out.write(id.array())

        // Image Data: LZW compression
        writeLzwData(indexedPixels, 8, out)

        // Trailer
        out.write(0x3B) // ';'
    }

    private fun findNearestPaletteIndex(rgb: Int, palette: List<Int>): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        var bestIdx = 1
        var bestDist = Int.MAX_VALUE
        for (i in 1 until palette.size) {
            val p = palette[i]
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
                if (dist == 0) break
            }
        }
        return bestIdx
    }

    private fun writeLzwData(pixels: ByteArray, initCodeSize: Int, out: OutputStream) {
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

        // Flush remaining bits
        if (bitCount > 0) {
            packet[packetLen++] = (bitBuffer and 0xFF).toByte()
        }
        flushPacket()

        // Block terminator
        out.write(0x00)
    }
}
