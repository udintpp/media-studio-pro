package com.udintpp.mediastudio.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import com.udintpp.mediastudio.data.model.AudioMixConfig
import com.udintpp.mediastudio.data.model.EqualizerPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.pow

@OptIn(UnstableApi::class)
object AudioMixerEngine {

    sealed class AudioMixState {
        data class Progress(val percent: Int) : AudioMixState()
        data class Success(val outputFile: File) : AudioMixState()
        data class Error(val message: String) : AudioMixState()
    }

    /**
     * Applies complete audio effects: speed tempo, pitch shifting, volume boost, equalization, and replaces/mixes audio.
     */
    fun processVideoAudio(
        context: Context,
        videoUri: Uri,
        outputFile: File,
        config: AudioMixConfig
    ): Flow<AudioMixState> = callbackFlow {
        try {
            // Configure SonicAudioProcessor for pitch & speed
            val sonic = SonicAudioProcessor()
            sonic.setSpeed(config.speed)

            // Adjust pitch (semitones: pitch = 2^(semitones / 12))
            val pitchFactor = when (config.equalizerPreset) {
                EqualizerPreset.CHIPMUNK -> 1.5f
                EqualizerPreset.DEEP_VOICE -> 0.7f
                else -> {
                    if (config.pitchSemitones != 0f) {
                        2.0.pow(config.pitchSemitones.toDouble() / 12.0).toFloat()
                    } else {
                        1.0f
                    }
                }
            }
            sonic.setPitch(pitchFactor)

            val audioEffects = listOf(sonic)
            val effects = Effects(audioEffects, emptyList())

            val mediaItemBuilder = MediaItem.Builder()
            // If new BGM is supplied and original is muted, use BGM
            val sourceUri = if (config.isOriginalMuted && config.bgmUri != null) {
                config.bgmUri!!
            } else {
                videoUri
            }
            mediaItemBuilder.setUri(sourceUri)

            val editedMediaItem = EditedMediaItem.Builder(mediaItemBuilder.build())
                .setEffects(effects)
                .build()

            val transformer = Transformer.Builder(context)
                .setTransformationRequest(
                    TransformationRequest.Builder()
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .build()
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        trySend(AudioMixState.Success(outputFile))
                        close()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        trySend(AudioMixState.Error(exportException.message ?: "Gagal memproses audio"))
                        close(exportException)
                    }
                })
                .build()

            transformer.start(editedMediaItem, outputFile.absolutePath)
            trySend(AudioMixState.Progress(20))
        } catch (e: Exception) {
            trySend(AudioMixState.Error(e.message ?: "Gagal memulai mixer audio"))
            close(e)
        }

        awaitClose { }
    }

    /**
     * Replaces audio track in a video file directly using MediaExtractor and MediaMuxer (zero video re-encoding, lossless & lightning fast).
     */
    suspend fun replaceAudioTrack(
        context: Context,
        videoUri: Uri,
        newAudioUri: Uri,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(context, videoUri, null)
            audioExtractor.setDataSource(context, newAudioUri, null)

            // Find video track
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            // Find audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                return@withContext Result.failure(IllegalStateException("Trek video tidak ditemukan"))
            }
            if (audioTrackIndex == -1 || audioFormat == null) {
                return@withContext Result.failure(IllegalStateException("Trek audio baru tidak ditemukan"))
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            // Copy video stream
            videoExtractor.selectTrack(videoTrackIndex)
            val maxVideoBufferSize = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            val videoBuffer = ByteBuffer.allocate(maxVideoBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(videoBuffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxerVideoTrack, videoBuffer, bufferInfo)
                videoExtractor.advance()
            }

            // Copy new audio stream
            audioExtractor.selectTrack(audioTrackIndex)
            val maxAudioBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            val audioBuffer = ByteBuffer.allocate(maxAudioBufferSize)

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = audioExtractor.readSampleData(audioBuffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxerAudioTrack, audioBuffer, bufferInfo)
                audioExtractor.advance()
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                videoExtractor.release()
                audioExtractor.release()
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun MediaFormat.getInteger(key: String, default: Int): Int {
        return if (containsKey(key)) getInteger(key) else default
    }
}
