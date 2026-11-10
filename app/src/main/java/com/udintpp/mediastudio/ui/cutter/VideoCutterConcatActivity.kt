package com.udintpp.mediastudio.ui.cutter

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.udintpp.mediastudio.R
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.data.model.VideoClip
import com.udintpp.mediastudio.databinding.ActivityVideoCutterConcatBinding
import com.udintpp.mediastudio.engine.video.VideoEffectTransformer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoCutterConcatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoCutterConcatBinding
    private var player: ExoPlayer? = null

    private var singleVideoUri: Uri? = null
    private var videoDurationMs: Long = 0L
    private var trimStartMs: Long = 0L
    private var trimEndMs: Long = 0L

    private val multiClips = mutableListOf<VideoClip>()
    private var currentMode = 0 // 0 = Potong, 1 = Sambung

    private val pickSingleVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            singleVideoUri = it
            loadSingleVideo(it)
        }
    }

    private val pickClipLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { u ->
                multiClips.add(VideoClip(uri = u, name = u.lastPathSegment ?: "Klip Video", durationMs = 0L))
            }
            updateClipsUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoCutterConcatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initPlayer()
        setupUI()
    }

    private fun initPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        binding.cutterPlayerView.player = exo
        player = exo

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && videoDurationMs == 0L) {
                    videoDurationMs = exo.duration.coerceAtLeast(1000L)
                    trimEndMs = videoDurationMs
                    setupTrimSlider()
                }
            }
        })
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.tabCutterMode.addTab(binding.tabCutterMode.newTab().setText("Mode Potong (Trim)"))
        binding.tabCutterMode.addTab(binding.tabCutterMode.newTab().setText("Mode Sambung (Gabung)"))

        binding.tabCutterMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentMode = tab?.position ?: 0
                if (currentMode == 0) {
                    binding.layoutModeTrim.visibility = View.VISIBLE
                    binding.layoutModeConcat.visibility = View.GONE
                } else {
                    binding.layoutModeTrim.visibility = View.GONE
                    binding.layoutModeConcat.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnPickSingleVideo.setOnClickListener {
            pickSingleVideoLauncher.launch("video/*")
        }

        binding.btnAddClip.setOnClickListener {
            pickClipLauncher.launch("video/*")
        }

        binding.rvClips.layoutManager = LinearLayoutManager(this)
        binding.rvClips.adapter = ClipsAdapter(multiClips) { removeIdx ->
            multiClips.removeAt(removeIdx)
            updateClipsUI()
        }

        binding.btnExportConcat.setOnClickListener {
            if (currentMode == 0) {
                exportTrimmedVideo()
            } else {
                exportConcatenatedVideos()
            }
        }
    }

    private fun loadSingleVideo(uri: Uri) {
        videoDurationMs = 0L
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun setupTrimSlider() {
        binding.cutterRangeSlider.valueFrom = 0f
        binding.cutterRangeSlider.valueTo = videoDurationMs.toFloat()
        binding.cutterRangeSlider.values = listOf(0f, videoDurationMs.toFloat())

        binding.tvCutterTrimStart.text = "Mulai: 00:00"
        binding.tvCutterTrimEnd.text = "Selesai: ${formatTime(videoDurationMs)}"

        binding.cutterRangeSlider.addOnChangeListener { slider, _, fromUser ->
            if (fromUser) {
                val vals = slider.values
                trimStartMs = vals[0].toLong()
                trimEndMs = vals[1].toLong()
                binding.tvCutterTrimStart.text = "Mulai: ${formatTime(trimStartMs)}"
                binding.tvCutterTrimEnd.text = "Selesai: ${formatTime(trimEndMs)}"
                player?.seekTo(trimStartMs)
            }
        }
    }

    private fun updateClipsUI() {
        binding.tvClipsCount.text = "Daftar Klip (${multiClips.size} Klip)"
        binding.rvClips.adapter?.notifyDataSetChanged()
    }

    private fun exportTrimmedVideo() {
        val uri = singleVideoUri ?: return
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MediaStudioPro")
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = File(outputDir, "Potong_Video_$timeStamp.mp4")

        Toast.makeText(this, "Mengekspor video terpotong...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            VideoEffectTransformer.transformVideo(
                context = this@VideoCutterConcatActivity,
                inputUri = uri,
                outputFile = outputFile,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                resolution = ExportResolution.FHD_1080P
            ).collectLatest { state ->
                when (state) {
                    is VideoEffectTransformer.TransformState.Success -> {
                        Toast.makeText(this@VideoCutterConcatActivity, "✅ Video selesai dipotong!\nFile: ${outputFile.name}", Toast.LENGTH_LONG).show()
                    }
                    is VideoEffectTransformer.TransformState.Error -> {
                        Toast.makeText(this@VideoCutterConcatActivity, "Gagal: ${state.message}", Toast.LENGTH_LONG).show()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun exportConcatenatedVideos() {
        if (multiClips.size < 2) {
            Toast.makeText(this, "Silakan pilih minimal 2 video untuk disambung", Toast.LENGTH_SHORT).show()
            return
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MediaStudioPro")
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = File(outputDir, "Sambung_Video_$timeStamp.mp4")

        Toast.makeText(this, "Menyambungkan ${multiClips.size} video...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            VideoEffectTransformer.concatenateVideos(
                context = this@VideoCutterConcatActivity,
                clips = multiClips,
                outputFile = outputFile,
                resolution = ExportResolution.FHD_1080P
            ).collectLatest { state ->
                when (state) {
                    is VideoEffectTransformer.TransformState.Success -> {
                        Toast.makeText(this@VideoCutterConcatActivity, "✅ Video berhasil digabungkan!\nFile: ${outputFile.name}", Toast.LENGTH_LONG).show()
                    }
                    is VideoEffectTransformer.TransformState.Error -> {
                        Toast.makeText(this@VideoCutterConcatActivity, "Gagal menyambung: ${state.message}", Toast.LENGTH_LONG).show()
                    }
                    else -> {}
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

    private class ClipsAdapter(
        private val items: List<VideoClip>,
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<ClipsAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tv_clip_name)
            val tvDuration: TextView = v.findViewById(R.id.tv_clip_duration)
            val btnRemove: ImageButton = v.findViewById(R.id.btn_remove_clip)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_clip, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = "Klip ${position + 1}: ${item.name}"
            holder.tvDuration.text = item.uri.toString()
            holder.btnRemove.setOnClickListener {
                onRemove(holder.adapterPosition)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
