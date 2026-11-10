package com.udintpp.mediastudio.ui.frame

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.udintpp.mediastudio.R
import com.udintpp.mediastudio.databinding.ActivityFrameExtractorBinding
import com.udintpp.mediastudio.engine.video.VideoFrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FrameExtractorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFrameExtractorBinding
    private var videoUri: Uri? = null
    private var videoDurationMs: Long = 0L
    private var currentPositionMs: Long = 0L
    private var currentBitmap: Bitmap? = null

    private val extractedFramesList = mutableListOf<VideoFrameExtractor.ExtractedFrame>()

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            videoUri = it
            loadVideoMetadata(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFrameExtractorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        pickVideoLauncher.launch("video/*")
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPickNewVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        binding.rvExtractedFrames.layoutManager = GridLayoutManager(this, 3)
        binding.rvExtractedFrames.adapter = FramesAdapter(extractedFramesList) { clickedFrame ->
            binding.ivFramePreview.setImageBitmap(clickedFrame.bitmap)
            binding.tvCurrentTimestamp.text = formatTimestamp(clickedFrame.timestampMs)
        }

        binding.sbVideoTimeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && videoDurationMs > 0) {
                    currentPositionMs = (progress.toFloat() / 1000f * videoDurationMs).toLong()
                    binding.tvCurrentTimestamp.text = formatTimestamp(currentPositionMs)
                    extractSingleFramePreview(currentPositionMs)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnCaptureSingleFrame.setOnClickListener {
            val bmp = currentBitmap ?: return@setOnClickListener
            lifecycleScope.launch {
                val res = VideoFrameExtractor.saveFrameToGallery(this@FrameExtractorActivity, bmp, currentPositionMs)
                if (res.isSuccess) {
                    Toast.makeText(this@FrameExtractorActivity, "✅ Frame foto berhasil disimpan ke Galeri!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FrameExtractorActivity, "Gagal menyimpan frame", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnBatchExtract.setOnClickListener {
            val uri = videoUri ?: return@setOnClickListener
            Toast.makeText(this, "Mengekstrak frame beruntun...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val res = VideoFrameExtractor.extractPeriodicFrames(
                    context = this@FrameExtractorActivity,
                    videoUri = uri,
                    intervalMs = 1500L,
                    maxFrames = 20
                )
                if (res.isSuccess) {
                    val frames = res.getOrNull() ?: emptyList()
                    extractedFramesList.clear()
                    extractedFramesList.addAll(frames)
                    binding.rvExtractedFrames.adapter?.notifyDataSetChanged()
                    Toast.makeText(this@FrameExtractorActivity, "Berhasil mengekstrak ${frames.size} frame!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FrameExtractorActivity, "Gagal: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadVideoMetadata(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@FrameExtractorActivity, uri)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                videoDurationMs = durStr?.toLongOrNull() ?: 5000L
                withContext(Dispatchers.Main) {
                    binding.sbVideoTimeline.max = 1000
                    extractSingleFramePreview(0L)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FrameExtractorActivity, "Gagal memuat metadata video", Toast.LENGTH_SHORT).show()
                }
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    private fun extractSingleFramePreview(timeMs: Long) {
        val uri = videoUri ?: return
        lifecycleScope.launch {
            val res = VideoFrameExtractor.extractFrameAt(this@FrameExtractorActivity, uri, timeMs)
            if (res.isSuccess) {
                val bmp = res.getOrNull()
                bmp?.let {
                    currentBitmap = it
                    binding.ivFramePreview.setImageBitmap(it)
                }
            }
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        val millis = ms % 1000
        return String.format("%02d:%02d.%03d", m, s, millis)
    }

    private class FramesAdapter(
        private val items: List<VideoFrameExtractor.ExtractedFrame>,
        private val onClick: (VideoFrameExtractor.ExtractedFrame) -> Unit
    ) : RecyclerView.Adapter<FramesAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val ivThumb: ImageView = v.findViewById(R.id.iv_frame_thumb)
            val tvTime: TextView = v.findViewById(R.id.tv_frame_time)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_frame, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.ivThumb.setImageBitmap(item.bitmap)
            val sec = item.timestampMs / 1000
            holder.tvTime.text = "${sec}s"
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
