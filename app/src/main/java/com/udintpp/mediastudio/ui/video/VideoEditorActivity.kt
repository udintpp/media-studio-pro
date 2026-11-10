package com.udintpp.mediastudio.ui.video

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.udintpp.mediastudio.data.model.AudioMixConfig
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.data.model.ImageAdjustments
import com.udintpp.mediastudio.databinding.ActivityVideoEditorBinding
import com.udintpp.mediastudio.engine.video.VideoEffectTransformer
import com.udintpp.mediastudio.ui.dialog.Export4KDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

        binding.btnExportVideo4k.setOnClickListener {
            showExportDialog()
        }
    }

    private fun showExportDialog() {
        Export4KDialog(this) { resolution ->
            startExport(resolution)
        }.show()
    }

    private fun startExport(resolution: ExportResolution) {
        val uri = videoUri ?: return
        player?.pause()

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MediaStudioPro")
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = File(outputDir, "MediaStudio_Video_${resolution.name}_$timeStamp.mp4")

        binding.layoutExportProgress.visibility = View.VISIBLE
        binding.tvProgressPercent.text = "Memproses video ${resolution.label}..."

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
                resolution = resolution
            ).collectLatest { state ->
                when (state) {
                    is VideoEffectTransformer.TransformState.Progress -> {
                        binding.tvProgressPercent.text = "Exporting: ${state.percentage}%"
                    }
                    is VideoEffectTransformer.TransformState.Success -> {
                        binding.layoutExportProgress.visibility = View.GONE
                        Toast.makeText(this@VideoEditorActivity, "✅ Video ${resolution.label} selesai diexport!\nDisimpan di: ${outputFile.name}", Toast.LENGTH_LONG).show()
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
