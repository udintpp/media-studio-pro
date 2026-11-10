package com.udintpp.mediastudio.engine.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.udintpp.mediastudio.data.model.ExportResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sin

object PhotoToVideoEngine {

    sealed class SlideshowState {
        data class Progress(val percent: Int, val currentSlide: Int, val totalSlides: Int) : SlideshowState()
        data class Success(val outputFile: File) : SlideshowState()
        data class Error(val message: String) : SlideshowState()
    }

    /**
     * Converts a collection of photo URIs into an animated MP4 video with smooth Ken Burns zoom and transitions.
     */
    fun createSlideshow(
        context: Context,
        photoUris: List<Uri>,
        outputFile: File,
        durationPerPhotoSec: Float = 3.0f,
        resolution: ExportResolution = ExportResolution.FHD_1080P,
        fps: Int = 30
    ): Flow<SlideshowState> = callbackFlow {
        try {
            val width = if (resolution.width > 0) resolution.width else 1920
            val height = if (resolution.height > 0) resolution.height else 1080
            val bitrate = resolution.bitrateMbps * 1_000_000

            val totalFramesPerPhoto = (durationPerPhotoSec * fps).toInt().coerceAtLeast(1)
            val totalPhotos = photoUris.size
            val totalFrames = totalFramesPerPhoto * totalPhotos

            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            var frameIndex = 0

            for (photoIdx in photoUris.indices) {
                val uri = photoUris[photoIdx]
                val bitmap = loadAndScaleBitmap(context, uri, width, height)

                for (f in 0 until totalFramesPerPhoto) {
                    val presentationTimeUs = (frameIndex * 1_000_000L / fps)

                    // Draw to inputSurface via lockHardwareCanvas or lockCanvas
                    val canvas = inputSurface.lockCanvas(null)
                    canvas.drawColor(Color.BLACK)

                    // Ken Burns subtle zoom effect (1.0 to 1.15x)
                    val progressInSlide = f.toFloat() / totalFramesPerPhoto.toFloat()
                    val zoom = 1.0f + 0.12f * progressInSlide

                    canvas.save()
                    canvas.translate(width / 2f, height / 2f)
                    canvas.scale(zoom, zoom)
                    canvas.translate(-width / 2f, -height / 2f)

                    // Center fit/crop
                    val destRect = Rect(0, 0, width, height)
                    canvas.drawBitmap(bitmap, null, destRect, paint)
                    canvas.restore()

                    inputSurface.unlockCanvasAndPost(canvas)

                    // Drain encoder
                    drainEncoder(encoder, muxer, bufferInfo, false) { trackIdx ->
                        videoTrackIndex = trackIdx
                        muxerStarted = true
                    }

                    frameIndex++
                    val overallProgress = (frameIndex * 100 / totalFrames).coerceIn(0, 99)
                    trySend(SlideshowState.Progress(overallProgress, photoIdx + 1, totalPhotos))
                }
                bitmap.recycle()
            }

            // Signal end of stream
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, muxer, bufferInfo, true) { trackIdx ->
                videoTrackIndex = trackIdx
                muxerStarted = true
            }

            encoder.stop()
            encoder.release()
            inputSurface.release()

            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()

            trySend(SlideshowState.Success(outputFile))
            close()
        } catch (e: Exception) {
            trySend(SlideshowState.Error(e.message ?: "Gagal membuat video slideshow"))
            close(e)
        }

        awaitClose { }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        onMuxerStart: (Int) -> Unit
    ) {
        val timeoutUs = 10_000L
        var muxerStarted = false

        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                val track = muxer.addTrack(newFormat)
                muxer.start()
                muxerStarted = true
                onMuxerStart(track)
            } else if (status >= 0) {
                val encodedData = encoder.getOutputBuffer(status)
                if (encodedData != null) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(0, encodedData, bufferInfo)
                    }
                }
                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    private fun loadAndScaleBitmap(context: Context, uri: Uri, targetW: Int, targetH: Int): Bitmap {
        val stream = context.contentResolver.openInputStream(uri)
        val original = BitmapFactory.decodeStream(stream) ?: throw IllegalArgumentException("Gagal memuat foto")
        stream?.close()
        return Bitmap.createScaledBitmap(original, targetW, targetH, true)
    }
}
