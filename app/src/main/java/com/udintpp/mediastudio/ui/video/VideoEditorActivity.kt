package com.udintpp.mediastudio.ui.video

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.udintpp.mediastudio.ui.vfx.AfterEffectsStudioActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.udintpp.mediastudio.data.model.AudioMixConfig
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.data.model.ImageAdjustments
import com.udintpp.mediastudio.databinding.ActivityVideoEditorBinding
import com.udintpp.mediastudio.engine.video.VideoEffectTransformer
import com.udintpp.mediastudio.ui.dialog.Export4KDialog
import com.udintpp.mediastudio.util.StorageExportHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoEditorBinding
    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null

    private var videoDurationMs: Long = 0L
    private var trimStartMs: Long = 0L
    private var trimEndMs: Long = 0L
    private var rotationDegrees: Float = 0f

    private val adjustments = ImageAdjustments()
    private val audioConfig = AudioMixConfig()

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            audioConfig.bgmUri = it
            Toast.makeText(this, "Audio baru berhasil dipilih untuk video", Toast.LENGTH_SHORT).show()
        }
    }

    private var activeKeyColor: Int? = null
    private var chromaTolerance: Float = 0.35f
    private var bgReplacementType = com.udintpp.mediastudio.engine.video.VideoBgReplacementType.TRANSPARENT
    private var replacementBgBitmap: android.graphics.Bitmap? = null
    private var replacementBgColor: Int? = null

    // Pick replacement photo for video background
    private val pickBgPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val stream = contentResolver.openInputStream(it)
                    val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                    stream?.close()
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (bmp != null) {
                            replacementBgBitmap = bmp
                            replacementBgColor = null
                            bgReplacementType = com.udintpp.mediastudio.engine.video.VideoBgReplacementType.PHOTO
                            binding.layoutMarkedColorInfo.visibility = View.VISIBLE
                            binding.tvSampledColorCode.text = "Latar Video Diganti: Foto Galeri"
                            updateChromaPreview()
                            Toast.makeText(this@VideoEditorActivity, "Background video diganti dengan foto dari galeri!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@VideoEditorActivity, "Gagal memuat foto background: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Pick replacement video for video background
    private val pickBgVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            bgReplacementType = com.udintpp.mediastudio.engine.video.VideoBgReplacementType.VIDEO
            binding.layoutMarkedColorInfo.visibility = View.VISIBLE
            binding.tvSampledColorCode.text = "Latar Video Diganti: Video Latar Baru"
            Toast.makeText(this, "Background video diganti dengan video dari galeri!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoUri = intent.data
        if (videoUri == null) {
            Toast.makeText(this, "Video tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initPlayer()
        setupListeners()
    }

    private fun initPlayer() {
        val exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer
        player = exoPlayer

        val mediaItem = MediaItem.fromUri(videoUri!!)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && videoDurationMs == 0L) {
                    videoDurationMs = exoPlayer.duration.coerceAtLeast(1000L)
                    trimEndMs = videoDurationMs
                    setupTrimSlider()
                }
            }
        })
    }

    private fun setupTrimSlider() {
        binding.rangeSliderTrim.valueFrom = 0f
        binding.rangeSliderTrim.valueTo = videoDurationMs.toFloat()
        binding.rangeSliderTrim.values = listOf(0f, videoDurationMs.toFloat())

        binding.tvTrimStart.text = "Mulai: 00:00"
        binding.tvTrimEnd.text = "Selesai: ${formatTime(videoDurationMs)}"

        binding.rangeSliderTrim.addOnChangeListener { slider, _, fromUser ->
            if (fromUser) {
                val vals = slider.values
                trimStartMs = vals[0].toLong()
                trimEndMs = vals[1].toLong()
                binding.tvTrimStart.text = "Mulai: ${formatTime(trimStartMs)}"
                binding.tvTrimEnd.text = "Selesai: ${formatTime(trimEndMs)}"
                player?.seekTo(trimStartMs)
            }
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnRotateVideo.setOnClickListener {
            rotationDegrees = (rotationDegrees + 90f) % 360f
            Toast.makeText(this, "Rotasi: ${rotationDegrees.toInt()}°", Toast.LENGTH_SHORT).show()
        }

        binding.btnChangeAudio.setOnClickListener {
            pickAudioLauncher.launch("audio/*")
        }

        binding.btnMuteAudio.setOnClickListener {
            audioConfig.isOriginalMuted = !audioConfig.isOriginalMuted
            binding.btnMuteAudio.text = if (audioConfig.isOriginalMuted) "Bisu: Aktif" else "Bisu: Nonaktif"
            player?.volume = if (audioConfig.isOriginalMuted) 0f else 1f
        }

        // Video Cropper Toggle
        binding.btnVideoCropper.setOnClickListener {
            val newState = !binding.videoCropOverlay.isCropModeActive
            binding.videoCropOverlay.isCropModeActive = newState
            binding.videoCropOverlay.isEyedropperActive = false
            if (newState) {
                binding.btnVideoCropper.text = "✅ Selesai Tandai Crop"
                Toast.makeText(this, "Geser lingkaran sudut penanda untuk memotong area video!", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnVideoCropper.text = "✂️ Tandai Kotak Crop"
                Toast.makeText(this, "Area crop video telah ditandai", Toast.LENGTH_SHORT).show()
            }
        }

        // Eyedropper Tandai Warna Transparan (Chroma Key)
        binding.btnVideoPipette.setOnClickListener {
            val uri = videoUri ?: return@setOnClickListener
            val newState = !binding.videoCropOverlay.isEyedropperActive
            binding.videoCropOverlay.isEyedropperActive = newState
            binding.videoCropOverlay.isCropModeActive = false
            if (newState) {
                player?.pause()
                val currentMs = player?.currentPosition ?: 0L
                Toast.makeText(this, "Sentuh layar video untuk memilih warna yang ingin dijadikan transparan!", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val frameRes = com.udintpp.mediastudio.engine.video.VideoFrameExtractor.extractFrameAt(this@VideoEditorActivity, uri, currentMs)
                    frameRes.getOrNull()?.let { bmp ->
                        binding.videoCropOverlay.currentVideoFrameBitmap = bmp
                    }
                }
            }
        }

        binding.videoCropOverlay.onColorPicked = { sampledColor ->
            activeKeyColor = sampledColor
            val hex = String.format("#%06X", 0xFFFFFF and sampledColor)
            binding.layoutMarkedColorInfo.visibility = View.VISIBLE
            binding.viewSampledColorPreview.setBackgroundColor(sampledColor)
            binding.tvSampledColorCode.text = "Warna Ditandai: $hex (Transparan)"
            binding.videoCropOverlay.isEyedropperActive = false
            updateChromaPreview()
            Toast.makeText(this, "Warna $hex ditandai untuk dihapus jadi transparan!", Toast.LENGTH_LONG).show()
        }

        // Video Background Tools
        binding.btnBgGreenScreen.setOnClickListener {
            activeKeyColor = android.graphics.Color.GREEN
            binding.layoutMarkedColorInfo.visibility = View.VISIBLE
            binding.viewSampledColorPreview.setBackgroundColor(android.graphics.Color.GREEN)
            binding.tvSampledColorCode.text = "Layar Hijau Ditandai (Transparan)"
            updateChromaPreview()
            Toast.makeText(this, "Preset Layar Hijau aktif (akan dihapus jadi transparan)", Toast.LENGTH_SHORT).show()
        }

        binding.btnBgBlueScreen.setOnClickListener {
            activeKeyColor = android.graphics.Color.BLUE
            binding.layoutMarkedColorInfo.visibility = View.VISIBLE
            binding.viewSampledColorPreview.setBackgroundColor(android.graphics.Color.BLUE)
            binding.tvSampledColorCode.text = "Layar Biru Ditandai (Transparan)"
            updateChromaPreview()
            Toast.makeText(this, "Preset Layar Biru aktif (akan dihapus jadi transparan)", Toast.LENGTH_SHORT).show()
        }

        binding.btnBgReplacePhoto.setOnClickListener {
            if (activeKeyColor == null) {
                activeKeyColor = android.graphics.Color.GREEN // Default key to green if not sampled
            }
            pickBgPhotoLauncher.launch("image/*")
        }

        binding.btnBgReplaceVideo.setOnClickListener {
            if (activeKeyColor == null) {
                activeKeyColor = android.graphics.Color.GREEN
            }
            pickBgVideoLauncher.launch("video/*")
        }

        binding.btnBgReplaceColor.setOnClickListener {
            val colors = arrayOf("⚪ Putih Bersih", "⚫ Hitam Elegan", "🔵 Studio Navy", "🟣 Neon Ungu", "🔴 Studio Merah")
            val colorVals = intArrayOf(
                android.graphics.Color.WHITE,
                android.graphics.Color.BLACK,
                android.graphics.Color.parseColor("#0F172A"),
                android.graphics.Color.parseColor("#8B5CF6"),
                android.graphics.Color.parseColor("#EF4444")
            )
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Pilih Warna Background Baru")
                .setItems(colors) { _, which ->
                    if (activeKeyColor == null) activeKeyColor = android.graphics.Color.GREEN
                    bgReplacementType = com.udintpp.mediastudio.engine.video.VideoBgReplacementType.SOLID_COLOR
                    replacementBgColor = colorVals[which]
                    replacementBgBitmap = null
                    binding.layoutMarkedColorInfo.visibility = View.VISIBLE
                    binding.tvSampledColorCode.text = "Latar Video Diganti: ${colors[which]}"
                    updateChromaPreview()
                    Toast.makeText(this, "Background diganti ke ${colors[which]}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        binding.btnBgPureTransparent.setOnClickListener {
            bgReplacementType = com.udintpp.mediastudio.engine.video.VideoBgReplacementType.TRANSPARENT
            replacementBgBitmap = null
            replacementBgColor = null
            binding.layoutMarkedColorInfo.visibility = View.VISIBLE
            binding.tvSampledColorCode.text = "Latar Video: Transparan Murni (Alpha)"
            updateChromaPreview()
            Toast.makeText(this, "Background video diatur transparan penuh", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetColorKey.setOnClickListener {
            activeKeyColor = null
            bgReplacementType = com.udintpp.mediastudio.engine.video.VideoBgReplacementType.TRANSPARENT
            replacementBgBitmap = null
            replacementBgColor = null
            binding.videoCropOverlay.chromaPreviewBitmap = null
            binding.layoutMarkedColorInfo.visibility = View.GONE
            Toast.makeText(this, "Pengaturan latar background video direset", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenAfterEffects.setOnClickListener {
            val uri = videoUri ?: return@setOnClickListener
            val intent = Intent(this, AfterEffectsStudioActivity::class.java).apply {
                data = uri
            }
            startActivity(intent)
        }

        binding.btnExportVideo4k.setOnClickListener {
            showExportDialog()
        }
    }

    private fun updateChromaPreview() {
        val uri = videoUri ?: return
        val key = activeKeyColor ?: run {
            binding.videoCropOverlay.chromaPreviewBitmap = null
            return
        }
        lifecycleScope.launch {
            val frame = binding.videoCropOverlay.currentVideoFrameBitmap ?: run {
                val currentMs = player?.currentPosition ?: 0L
                val frameRes = com.udintpp.mediastudio.engine.video.VideoFrameExtractor.extractFrameAt(this@VideoEditorActivity, uri, currentMs)
                frameRes.getOrNull()
            }
            if (frame != null) {
                binding.videoCropOverlay.currentVideoFrameBitmap = frame
                val preview = com.udintpp.mediastudio.engine.video.VideoChromaKeyEngine.processFrame(
                    frame = frame,
                    keyColor = key,
                    tolerance = chromaTolerance,
                    replacementType = bgReplacementType,
                    replacementBgBitmap = replacementBgBitmap,
                    replacementBgColor = replacementBgColor,
                    drawCheckerboardIfTransparent = true
                )
                binding.videoCropOverlay.chromaPreviewBitmap = preview
            }
        }
    }

    private fun showExportDialog() {
        com.udintpp.mediastudio.ui.dialog.ExportMediaDialog.forVideo(this) { resolution, format ->
            startExport(resolution, format)
        }.show()
    }

    private fun startExport(resolution: ExportResolution, format: com.udintpp.mediastudio.data.model.VideoExportFormat) {
        val uri = videoUri ?: return
        player?.pause()

        val outputFile = StorageExportHelper.createVideoOutputFile(
            context = this@VideoEditorActivity,
            prefix = "MediaStudio_Video_${resolution.name}",
            extension = format.extension
        )

        binding.layoutExportProgress.visibility = View.VISIBLE
        binding.tvProgressPercent.text = "Memproses video ${resolution.label} (${format.displayName})..."

        val cropNorm = if (binding.videoCropOverlay.isCropModeActive || !binding.videoCropOverlay.cropRect.isEmpty) {
            binding.videoCropOverlay.getNormalizedCropRect()
        } else {
            null
        }

        lifecycleScope.launch {
            VideoEffectTransformer.transformVideo(
                context = this@VideoEditorActivity,
                inputUri = uri,
                outputFile = outputFile,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                rotationDegrees = rotationDegrees,
                adjustments = adjustments,
                audioConfig = audioConfig,
                resolution = resolution,
                cropRectNormalized = cropNorm,
                exportFormat = format,
                keyColor = activeKeyColor,
                chromaTolerance = chromaTolerance,
                replacementType = bgReplacementType,
                replacementBgBitmap = replacementBgBitmap,
                replacementBgColor = replacementBgColor
            ).collectLatest { state ->
                when (state) {
                    is VideoEffectTransformer.TransformState.Progress -> {
                        binding.tvProgressPercent.text = "Exporting: ${state.percentage}%"
                    }
                    is VideoEffectTransformer.TransformState.Success -> {
                        StorageExportHelper.syncExportedFile(this@VideoEditorActivity, outputFile, format.mimeType)
                        val successMsg = StorageExportHelper.getSuccessNotificationMessage(outputFile.name)
                        binding.layoutExportProgress.visibility = View.GONE
                        Toast.makeText(this@VideoEditorActivity, successMsg, Toast.LENGTH_LONG).show()
                    }
                    is VideoEffectTransformer.TransformState.Error -> {
                        binding.layoutExportProgress.visibility = View.GONE
                        Toast.makeText(this@VideoEditorActivity, "Gagal export: ${state.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
