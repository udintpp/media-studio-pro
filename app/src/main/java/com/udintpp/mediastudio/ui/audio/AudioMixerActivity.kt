package com.udintpp.mediastudio.ui.audio

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.udintpp.mediastudio.data.model.AudioMixConfig
import com.udintpp.mediastudio.data.model.EqualizerPreset
import com.udintpp.mediastudio.databinding.ActivityAudioMixerBinding
import com.udintpp.mediastudio.engine.audio.AudioMixerEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioMixerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioMixerBinding
    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null

    private val audioConfig = AudioMixConfig()

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            videoUri = it
            loadVideo(it)
        }
    }

    private val pickBgmLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            audioConfig.bgmUri = it
            audioConfig.bgmName = it.lastPathSegment ?: "BGM Track"
            binding.tvBgmTrackTitle.text = "Track 2: ${audioConfig.bgmName}"
            Toast.makeText(this, "Lagu latar berhasil dipilih", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioMixerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initPlayer()
        setupUI()
        pickVideoLauncher.launch("video/*")
    }

    private fun initPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        binding.audioPlayerView.player = exo
        player = exo
    }

    private fun loadVideo(uri: Uri) {
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        // Track 1: Original Audio Volume
        binding.sliderOrigVol.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                audioConfig.originalAudioVolume = value / 100f
                binding.tvOrigVolVal.text = "${value.toInt()}%"
                if (!audioConfig.isOriginalMuted) {
                    player?.volume = audioConfig.originalAudioVolume
                }
            }
        }

        binding.cbMuteOrig.setOnCheckedChangeListener { _, isChecked ->
            audioConfig.isOriginalMuted = isChecked
            player?.volume = if (isChecked) 0f else audioConfig.originalAudioVolume
        }

        // Track 2: BGM
        binding.btnSelectBgmTrack.setOnClickListener {
            pickBgmLauncher.launch("audio/*")
        }

        binding.sliderBgmVol.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                audioConfig.bgmVolume = value / 100f
                binding.tvBgmVolVal.text = "${value.toInt()}%"
            }
        }

        // Audio Effects: Speed
        binding.sliderAudioSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                audioConfig.speed = value
                binding.tvAudioSpeedVal.text = "${value}x"
                player?.setPlaybackSpeed(value)
            }
        }

        // Audio Effects: Pitch
        binding.sliderAudioPitch.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                audioConfig.pitchSemitones = value
                binding.tvAudioPitchVal.text = "${value.toInt()} st"
            }
        }

        // Equalizer Presets
        val presets = EqualizerPreset.values()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets.map { it.label })
        binding.spinnerEqualizer.adapter = adapter
        binding.spinnerEqualizer.setSelection(EqualizerPreset.FLAT.ordinal)

        // Export button
        binding.btnExportAudioVideo.setOnClickListener {
            startAudioExport()
        }
    }

    private fun startAudioExport() {
        val uri = videoUri ?: run {
            Toast.makeText(this, "Silakan pilih video terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }

        audioConfig.equalizerPreset = EqualizerPreset.values()[binding.spinnerEqualizer.selectedItemPosition]

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MediaStudioPro")
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = File(outputDir, "AudioMix_$timeStamp.mp4")

        Toast.makeText(this, "Memproses efek & mixer audio...", Toast.LENGTH_SHORT).show()
        binding.btnExportAudioVideo.isEnabled = false

        lifecycleScope.launch {
            AudioMixerEngine.processVideoAudio(
                context = this@AudioMixerActivity,
                videoUri = uri,
                outputFile = outputFile,
                config = audioConfig
            ).collectLatest { state ->
                when (state) {
                    is AudioMixerEngine.AudioMixState.Success -> {
                        binding.btnExportAudioVideo.isEnabled = true
                        Toast.makeText(this@AudioMixerActivity, "✅ Video & Efek Audio berhasil disimpan!\nFile: ${outputFile.name}", Toast.LENGTH_LONG).show()
                    }
                    is AudioMixerEngine.AudioMixState.Error -> {
                        binding.btnExportAudioVideo.isEnabled = true
                        Toast.makeText(this@AudioMixerActivity, "Gagal: ${state.message}", Toast.LENGTH_LONG).show()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
