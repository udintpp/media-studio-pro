package com.udintpp.mediastudio.ui.slideshow

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.udintpp.mediastudio.R
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.databinding.ActivitySlideshowBinding
import com.udintpp.mediastudio.engine.video.PhotoToVideoEngine
import com.udintpp.mediastudio.util.StorageExportHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SlideshowActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySlideshowBinding
    private val selectedPhotos = mutableListOf<Uri>()
    private var selectedBgmUri: Uri? = null
    private var durationPerSlide: Float = 3.0f

    private val pickPhotosLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            selectedPhotos.addAll(uris)
            updatePhotosUI()
        }
    }

    private val pickBgmLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedBgmUri = it
            binding.tvBgmName.text = "Musik terpilih: ${it.lastPathSegment ?: "Audio"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySlideshowBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.rvSelectedPhotos.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSelectedPhotos.adapter = PhotosAdapter(selectedPhotos) { removedIndex ->
            selectedPhotos.removeAt(removedIndex)
            updatePhotosUI()
        }

        binding.btnPickPhotos.setOnClickListener {
            pickPhotosLauncher.launch("image/*")
        }

        binding.btnPickBgm.setOnClickListener {
            pickBgmLauncher.launch("audio/*")
        }

        binding.sliderDuration.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                durationPerSlide = value
                binding.tvDurationVal.text = String.format("%.1fs", value)
            }
        }

        // Setup resolution spinner
        val resolutions = ExportResolution.values()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions.map { it.label })
        binding.spinnerSlideshowResolution.adapter = adapter
        binding.spinnerSlideshowResolution.setSelection(ExportResolution.FHD_1080P.ordinal)

        binding.btnRenderSlideshow.setOnClickListener {
            if (selectedPhotos.isEmpty()) {
                Toast.makeText(this, "Silakan pilih minimal 1 foto", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startSlideshowRendering()
        }
    }

    private fun updatePhotosUI() {
        binding.tvPhotoCount.text = "Daftar Foto (${selectedPhotos.size} Foto)"
        binding.rvSelectedPhotos.adapter?.notifyDataSetChanged()
    }

    private fun startSlideshowRendering() {
        val selectedResolution = ExportResolution.values()[binding.spinnerSlideshowResolution.selectedItemPosition]
        val outputFile = StorageExportHelper.createVideoOutputFile(
            context = this@SlideshowActivity,
            prefix = "Slideshow_${selectedResolution.name}",
            extension = "mp4"
        )

        binding.pbSlideshow.visibility = View.VISIBLE
        binding.tvSlideshowProgressText.visibility = View.VISIBLE
        binding.btnRenderSlideshow.isEnabled = false

        lifecycleScope.launch {
            PhotoToVideoEngine.createSlideshow(
                context = this@SlideshowActivity,
                photoUris = selectedPhotos,
                outputFile = outputFile,
                durationPerPhotoSec = durationPerSlide,
                resolution = selectedResolution
            ).collectLatest { state ->
                when (state) {
                    is PhotoToVideoEngine.SlideshowState.Progress -> {
                        binding.pbSlideshow.progress = state.percent
                        binding.tvSlideshowProgressText.text = "Merender video: ${state.percent}% (Slide ${state.currentSlide}/${state.totalSlides})"
                    }
                    is PhotoToVideoEngine.SlideshowState.Success -> {
                        StorageExportHelper.syncExportedFile(this@SlideshowActivity, outputFile, "video/mp4")
                        val successMsg = StorageExportHelper.getSuccessNotificationMessage(outputFile.name)
                        binding.pbSlideshow.visibility = View.GONE
                        binding.tvSlideshowProgressText.visibility = View.GONE
                        binding.btnRenderSlideshow.isEnabled = true
                        Toast.makeText(this@SlideshowActivity, successMsg, Toast.LENGTH_LONG).show()
                    }
                    is PhotoToVideoEngine.SlideshowState.Error -> {
                        binding.pbSlideshow.visibility = View.GONE
                        binding.tvSlideshowProgressText.visibility = View.GONE
                        binding.btnRenderSlideshow.isEnabled = true
                        Toast.makeText(this@SlideshowActivity, "Gagal membuat video: ${state.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private class PhotosAdapter(
        private val items: List<Uri>,
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<PhotosAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val ivThumb: ImageView = v.findViewById(R.id.iv_frame_thumb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_frame, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val uri = items[position]
            Glide.with(holder.itemView.context).load(uri).into(holder.ivThumb)
            holder.itemView.setOnClickListener {
                onRemove(holder.adapterPosition)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
