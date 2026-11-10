package com.udintpp.mediastudio.engine.video

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import com.udintpp.mediastudio.data.model.AudioMixConfig
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.data.model.ImageAdjustments
import com.udintpp.mediastudio.data.model.VideoClip
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

@OptIn(UnstableApi::class)
object VideoEffectTransformer {

    sealed class TransformState {
        data class Progress(val percentage: Int) : TransformState()
        data class Success(val outputFile: File) : TransformState()
        data class Error(val message: String) : TransformState()
    }

    /**
     * Transforms video with trimming, rotation, brightness/contrast, audio swap/mute, and export resolution up to 4K.
     */
    fun transformVideo(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        trimStartMs: Long = 0L,
        trimEndMs: Long = 0L,
        rotationDegrees: Float = 0f,
        adjustments: ImageAdjustments = ImageAdjustments(),
        audioConfig: AudioMixConfig = AudioMixConfig(),
        resolution: ExportResolution = ExportResolution.FHD_1080P
    ): Flow<TransformState> = callbackFlow {
        try {
            // Build MediaItem with clipping if specified
            val mediaItemBuilder = MediaItem.Builder().setUri(inputUri)
            if (trimEndMs > trimStartMs && trimEndMs > 0) {
                mediaItemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(trimStartMs)
                        .setEndPositionMs(trimEndMs)
                        .build()
                )
            }
            val mediaItem = mediaItemBuilder.build()

            // Video Effects (Rotation, Scale, Brightness, Contrast)
            val videoEffects = mutableListOf<Effect>()

            if (rotationDegrees != 0f) {
                val rotateEffect = ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(rotationDegrees)
                    .build()
                videoEffects.add(rotateEffect)
            }

            if (adjustments.brightness != 0f) {
                // Media3 Brightness takes range -1.0f to 1.0f
                val bVal = (adjustments.brightness / 100f).coerceIn(-1.0f, 1.0f)
                videoEffects.add(Brightness(bVal))
            }

            if (adjustments.contrast != 1.0f) {
                // Media3 Contrast takes range -1.0f to 1.0f (0 is neutral)
                val cVal = (adjustments.contrast - 1.0f).coerceIn(-1.0f, 1.0f)
                videoEffects.add(Contrast(cVal))
            }

            if (adjustments.saturation == 0f) {
                videoEffects.add(RgbFilter.createGrayscaleFilter())
            }

            if (resolution.height > 0) {
                videoEffects.add(androidx.media3.effect.Presentation.createForHeight(resolution.height))
            }

            val effects = Effects(emptyList(), videoEffects)

            val editedMediaItemBuilder = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)

            // Audio configuration: mute original or replace
            if (audioConfig.isOriginalMuted || audioConfig.originalAudioVolume == 0f) {
                editedMediaItemBuilder.setRemoveAudio(true)
            }

            val editedMediaItem = editedMediaItemBuilder.build()

            // Transformer setup
            val transformerBuilder = Transformer.Builder(context)

            // Configure target resolution and video MIME (H.264 / H.265 for 4K)
            val videoMime = if (resolution == ExportResolution.UHD_4K) {
                MimeTypes.VIDEO_H265
            } else {
                MimeTypes.VIDEO_H264
            }

            val transReqBuilder = TransformationRequest.Builder()
                .setVideoMimeType(videoMime)

            transformerBuilder.setTransformationRequest(transReqBuilder.build())

            transformerBuilder.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    trySend(TransformState.Success(outputFile))
                    close()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    trySend(TransformState.Error(exportException.message ?: "Kesalahan saat memproses video"))
                    close(exportException)
                }
            })

            val transformer = transformerBuilder.build()
            transformer.start(editedMediaItem, outputFile.absolutePath)

            trySend(TransformState.Progress(10))
        } catch (e: Exception) {
            trySend(TransformState.Error(e.message ?: "Gagal memulai pemrosesan video"))
            close(e)
        }

        awaitClose { }
    }

    /**
     * Concatenates multiple video clips sequentially into a single video file.
     */
    fun concatenateVideos(
        context: Context,
        clips: List<VideoClip>,
        outputFile: File,
        resolution: ExportResolution = ExportResolution.FHD_1080P
    ): Flow<TransformState> = callbackFlow {
        try {
            val concatEffects = if (resolution.height > 0) {
                Effects(emptyList(), listOf(androidx.media3.effect.Presentation.createForHeight(resolution.height)))
            } else {
                Effects(emptyList(), emptyList())
            }

            val editedItems = clips.map { clip ->
                val miBuilder = MediaItem.Builder().setUri(clip.uri)
                if (clip.endTrimMs > clip.startTrimMs && clip.endTrimMs > 0) {
                    miBuilder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.startTrimMs)
                            .setEndPositionMs(clip.endTrimMs)
                            .build()
                    )
                }
                EditedMediaItem.Builder(miBuilder.build())
                    .setEffects(concatEffects)
                    .build()
            }

            val sequence = EditedMediaItemSequence(editedItems)
            val composition = Composition.Builder(listOf(sequence)).build()

            val transformerBuilder = Transformer.Builder(context)
            transformerBuilder.setTransformationRequest(
                TransformationRequest.Builder()
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .build()
            )

            transformerBuilder.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    trySend(TransformState.Success(outputFile))
                    close()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    trySend(TransformState.Error(exportException.message ?: "Gagal menyambung video"))
                    close(exportException)
                }
            })

            val transformer = transformerBuilder.build()
            transformer.start(composition, outputFile.absolutePath)

            trySend(TransformState.Progress(15))
        } catch (e: Exception) {
            trySend(TransformState.Error(e.message ?: "Gagal menyambung video"))
            close(e)
        }

        awaitClose { }
    }
}
