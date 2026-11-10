package com.udintpp.mediastudio.ui.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.udintpp.mediastudio.R
import com.udintpp.mediastudio.data.model.AspectRatio
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.data.model.FilterPreset
import com.udintpp.mediastudio.data.model.ImageAdjustments
import com.udintpp.mediastudio.databinding.ActivityPhotoEditorBinding
import com.udintpp.mediastudio.engine.photo.BackgroundRemover
import com.udintpp.mediastudio.engine.photo.PhotoExporter
import com.udintpp.mediastudio.engine.photo.PhotoProcessor
import com.udintpp.mediastudio.ui.dialog.EmojiStickerDialog
import com.udintpp.mediastudio.ui.dialog.Export4KDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoEditorBinding
    private var originalBitmap: Bitmap? = null
    private var currentProcessedBitmap: Bitmap? = null
    private val adjustments = ImageAdjustments()

    private enum class ActiveTool {
        BRIGHTNESS, CONTRAST, SATURATION, BEAUTY, WARMTH, BG_REMOVE, CROP, FILTER, ROTATE
    }
    private var currentTool = ActiveTool.BRIGHTNESS

    // Gallery picker for custom background image
    private val pickBgImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            loadBackgroundImage(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imageUri = intent.data
        if (imageUri != null) {
            loadImage(imageUri)
        } else {
            Toast.makeText(this, "Foto tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
        }

        setupUI()
    }

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(stream)
                stream?.close()
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        originalBitmap = bmp
                        currentProcessedBitmap = bmp
                        binding.photoCanvas.baseBitmap = bmp
                    } else {
                        Toast.makeText(this@PhotoEditorActivity, "Gagal membaca foto", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PhotoEditorActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun loadBackgroundImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri)
                val bgBmp = BitmapFactory.decodeStream(stream)
                stream?.close()
                withContext(Dispatchers.Main) {
                    binding.photoCanvas.backgroundBitmap = bgBmp
                    binding.photoCanvas.backgroundColorOverride = null
                    Toast.makeText(this@PhotoEditorActivity, "Background gambar diganti", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PhotoEditorActivity, "Gagal memuat background: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        // Sticker & Emoji Dialog
        binding.btnAddSticker.setOnClickListener {
            EmojiStickerDialog(this) { sticker ->
                binding.photoCanvas.addSticker(sticker)
            }.show()
        }

        // Delete active sticker
        binding.btnDeleteActiveSticker.setOnClickListener {
            binding.photoCanvas.removeSelectedSticker()
        }

        // Export 4K
        binding.btnExport4k.setOnClickListener {
            showExportDialog()
        }

        // Setup bottom category buttons
        binding.btnToolBrightness.setOnClickListener { selectTool(ActiveTool.BRIGHTNESS) }
        binding.btnToolContrast.setOnClickListener { selectTool(ActiveTool.CONTRAST) }
        binding.btnToolSaturation.setOnClickListener { selectTool(ActiveTool.SATURATION) }
        binding.btnToolBeauty.setOnClickListener { selectTool(ActiveTool.BEAUTY) }
        binding.btnToolBgRemove.setOnClickListener { selectTool(ActiveTool.BG_REMOVE) }
        binding.btnToolFilter.setOnClickListener { selectTool(ActiveTool.FILTER) }
        binding.btnToolCrop.setOnClickListener { selectTool(ActiveTool.CROP) }
        binding.btnToolRotate.setOnClickListener { selectTool(ActiveTool.ROTATE) }

        // Slider change listener
        binding.sliderTool.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                when (currentTool) {
                    ActiveTool.BRIGHTNESS -> {
                        adjustments.brightness = value
                        binding.tvActiveToolValue.text = "${value.toInt()}"
                    }
                    ActiveTool.CONTRAST -> {
                        // value is 20 to 250 -> 0.2x to 2.5x
                        adjustments.contrast = value / 100f
                        binding.tvActiveToolValue.text = String.format("%.2fx", adjustments.contrast)
                    }
                    ActiveTool.SATURATION -> {
                        adjustments.saturation = value / 100f
                        binding.tvActiveToolValue.text = String.format("%.2fx", adjustments.saturation)
                    }
                    ActiveTool.BEAUTY -> {
                        adjustments.beauty = value
                        binding.tvActiveToolValue.text = "${value.toInt()}%"
                        applyProcessedFilters()
                    }
                    ActiveTool.WARMTH -> {
                        adjustments.warmth = value
                        binding.tvActiveToolValue.text = "${value.toInt()}"
                    }
                    else -> {}
                }
                binding.photoCanvas.adjustments = adjustments
            }
        }

        // Default tool
        selectTool(ActiveTool.BRIGHTNESS)
    }

    private fun selectTool(tool: ActiveTool) {
        currentTool = tool
        binding.layoutSubButtons.removeAllViews()

        when (tool) {
            ActiveTool.BRIGHTNESS -> {
                binding.layoutSliderPanel.visibility = View.VISIBLE
                binding.tvActiveToolName.text = "Kecerahan (Brightness)"
                binding.sliderTool.valueFrom = -100f
                binding.sliderTool.valueTo = 100f
                binding.sliderTool.value = adjustments.brightness
                binding.tvActiveToolValue.text = "${adjustments.brightness.toInt()}"
            }
            ActiveTool.CONTRAST -> {
                binding.layoutSliderPanel.visibility = View.VISIBLE
                binding.tvActiveToolName.text = "Kontras (Contrast)"
                binding.sliderTool.valueFrom = 20f
                binding.sliderTool.valueTo = 250f
                binding.sliderTool.value = adjustments.contrast * 100f
                binding.tvActiveToolValue.text = String.format("%.2fx", adjustments.contrast)
            }
            ActiveTool.SATURATION -> {
                binding.layoutSliderPanel.visibility = View.VISIBLE
                binding.tvActiveToolName.text = "Saturasi Warna"
                binding.sliderTool.valueFrom = 0f
                binding.sliderTool.valueTo = 250f
                binding.sliderTool.value = adjustments.saturation * 100f
                binding.tvActiveToolValue.text = String.format("%.2fx", adjustments.saturation)
            }
            ActiveTool.BEAUTY -> {
                binding.layoutSliderPanel.visibility = View.VISIBLE
                binding.tvActiveToolName.text = "Percantik (Penghalus Kulit)"
                binding.sliderTool.valueFrom = 0f
                binding.sliderTool.valueTo = 100f
                binding.sliderTool.value = adjustments.beauty
                binding.tvActiveToolValue.text = "${adjustments.beauty.toInt()}%"
            }
            ActiveTool.BG_REMOVE -> {
                binding.layoutSliderPanel.visibility = View.GONE
                setupBgRemovalSubMenu()
            }
            ActiveTool.FILTER -> {
                binding.layoutSliderPanel.visibility = View.GONE
                setupFilterPresetsSubMenu()
            }
            ActiveTool.CROP -> {
                binding.layoutSliderPanel.visibility = View.GONE
                setupCropSubMenu()
            }
            ActiveTool.ROTATE -> {
                binding.layoutSliderPanel.visibility = View.GONE
                setupRotateSubMenu()
            }
            else -> {}
        }
    }

    private fun setupBgRemovalSubMenu() {
        addSubButton("✂️ Hapus Background") {
            originalBitmap?.let { src ->
                Toast.makeText(this, "Memproses pemisahan background...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.Default) {
                    val transparentCutout = BackgroundRemover.removeBackground(src, tolerance = 48f)
                    withContext(Dispatchers.Main) {
                        currentProcessedBitmap = transparentCutout
                        binding.photoCanvas.baseBitmap = transparentCutout
                        Toast.makeText(this@PhotoEditorActivity, "Background berhasil dihapus transparan!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        addSubButton("⚪ Ganti BG Putih") {
            binding.photoCanvas.backgroundColorOverride = Color.WHITE
        }

        addSubButton("⚫ Ganti BG Hitam") {
            binding.photoCanvas.backgroundColorOverride = Color.BLACK
        }

        addSubButton("🟣 Ganti BG Neon") {
            binding.photoCanvas.backgroundColorOverride = Color.parseColor("#8B5CF6")
        }

        addSubButton("🖼️ Ganti BG dari Galeri") {
            pickBgImageLauncher.launch("image/*")
        }

        addSubButton("🔄 Reset BG") {
            binding.photoCanvas.backgroundColorOverride = null
            binding.photoCanvas.backgroundBitmap = null
            binding.photoCanvas.baseBitmap = originalBitmap
        }
    }

    private fun setupFilterPresetsSubMenu() {
        for (preset in FilterPreset.values()) {
            addSubButton(preset.displayName) {
                adjustments.filterPreset = preset
                applyProcessedFilters()
            }
        }
    }

    private fun setupCropSubMenu() {
        for (ratio in AspectRatio.values()) {
            addSubButton(ratio.label) {
                originalBitmap?.let { src ->
                    val cropped = PhotoProcessor.cropToAspectRatio(src, ratio)
                    currentProcessedBitmap = cropped
                    binding.photoCanvas.baseBitmap = cropped
                }
            }
        }
    }

    private fun setupRotateSubMenu() {
        addSubButton("Putar 90°") {
            currentProcessedBitmap?.let { src ->
                val rotated = PhotoProcessor.rotateAndFlip(src, 90f, flipHorizontal = false, flipVertical = false)
                currentProcessedBitmap = rotated
                binding.photoCanvas.baseBitmap = rotated
            }
        }
        addSubButton("Flip Horizontal") {
            currentProcessedBitmap?.let { src ->
                val flipped = PhotoProcessor.rotateAndFlip(src, 0f, flipHorizontal = true, flipVertical = false)
                currentProcessedBitmap = flipped
                binding.photoCanvas.baseBitmap = flipped
            }
        }
        addSubButton("Flip Vertikal") {
            currentProcessedBitmap?.let { src ->
                val flipped = PhotoProcessor.rotateAndFlip(src, 0f, flipHorizontal = false, flipVertical = true)
                currentProcessedBitmap = flipped
                binding.photoCanvas.baseBitmap = flipped
            }
        }
    }

    private fun addSubButton(text: String, onClick: () -> Unit) {
        val btn = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            textSize = 12f
            setOnClickListener { onClick() }
        }
        binding.layoutSubButtons.addView(btn)
    }

    private fun applyProcessedFilters() {
        val base = currentProcessedBitmap ?: originalBitmap ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            val processed = PhotoProcessor.processImage(base, adjustments)
            withContext(Dispatchers.Main) {
                binding.photoCanvas.baseBitmap = processed
            }
        }
    }

    private fun showExportDialog() {
        val base = binding.photoCanvas.baseBitmap ?: return
        Export4KDialog(this) { resolution ->
            Toast.makeText(this, "Mengekspor gambar dalam resolusi ${resolution.label}...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val result = PhotoExporter.exportPhoto(
                    context = this@PhotoEditorActivity,
                    baseBitmap = base,
                    stickers = binding.photoCanvas.stickers,
                    resolution = resolution
                )
                if (result.isSuccess) {
                    Toast.makeText(this@PhotoEditorActivity, "✅ Berhasil disimpan ke Galeri (${resolution.label})!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@PhotoEditorActivity, "Gagal mengekspor: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.show()
    }
}
