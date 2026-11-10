package com.udintpp.mediastudio.ui.photo

import android.content.Intent
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
import com.udintpp.mediastudio.ui.vfx.AfterEffectsStudioActivity
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
                        currentProcessedBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
                        binding.photoCanvas.originalUncutBitmap = bmp
                        binding.photoCanvas.baseBitmap = currentProcessedBitmap
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
        binding.btnToolAfterEffects.setOnClickListener {
            val uri = intent.data ?: return@setOnClickListener
            val intent = Intent(this, AfterEffectsStudioActivity::class.java).apply {
                data = uri
            }
            startActivity(intent)
        }

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
        addSubButton("🧠 AI Hapus BG (Deteksi Orang Presisi)") {
            originalBitmap?.let { src ->
                binding.photoCanvas.brushMode = BrushMode.NONE
                binding.photoCanvas.backgroundColorOverride = null
                binding.photoCanvas.backgroundBitmap = null
                Toast.makeText(this, "AI sedang mendeteksi figur orang & pakaian secara presisi...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val result = BackgroundRemover.removeBackgroundAI(src, confidenceThreshold = 0.5f)
                    if (result.isSuccess) {
                        val transparentCutout = result.getOrNull()
                        if (transparentCutout != null) {
                            currentProcessedBitmap = transparentCutout.copy(Bitmap.Config.ARGB_8888, true)
                            binding.photoCanvas.baseBitmap = currentProcessedBitmap
                            Toast.makeText(this@PhotoEditorActivity, "✅ Sukses! Background dihapus jadi transparan murni.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // Fallback to Smart Flood Fill if AI encountered error
                        Toast.makeText(this@PhotoEditorActivity, "AI gagal, menggunakan Flood Fill cerdas...", Toast.LENGTH_SHORT).show()
                        val cutout = BackgroundRemover.removeBackgroundSmartColor(src, tolerance = 35f)
                        currentProcessedBitmap = cutout.copy(Bitmap.Config.ARGB_8888, true)
                        binding.photoCanvas.baseBitmap = currentProcessedBitmap
                    }
                }
            }
        }

        addSubButton("⚡ Flood Fill BG (Warna Tepi)") {
            originalBitmap?.let { src ->
                binding.photoCanvas.brushMode = BrushMode.NONE
                binding.photoCanvas.backgroundColorOverride = null
                binding.photoCanvas.backgroundBitmap = null
                Toast.makeText(this, "Memproses pemisahan background warna tepi...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.Default) {
                    val transparentCutout = BackgroundRemover.removeBackgroundSmartColor(src, tolerance = 35f)
                    withContext(Dispatchers.Main) {
                        currentProcessedBitmap = transparentCutout.copy(Bitmap.Config.ARGB_8888, true)
                        binding.photoCanvas.baseBitmap = currentProcessedBitmap
                        Toast.makeText(this@PhotoEditorActivity, "Background tepi dihapus jadi transparan", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        addSubButton("🎯 Tandai Warna Transparan (Pipet)") {
            binding.photoCanvas.brushMode = BrushMode.NONE
            binding.photoCanvas.isCropModeActive = false
            binding.photoCanvas.isEyedropperActive = true
            binding.photoCanvas.backgroundColorOverride = null
            binding.photoCanvas.backgroundBitmap = null
            Toast.makeText(this, "Sentuh/geser jari pada warna foto yang ingin dijadikan transparan!", Toast.LENGTH_LONG).show()

            binding.photoCanvas.onColorSampled = { sampledColor ->
                binding.photoCanvas.isEyedropperActive = false
                val hex = String.format("#%06X", 0xFFFFFF and sampledColor)
                Toast.makeText(this@PhotoEditorActivity, "Warna $hex dijadikan transparan!", Toast.LENGTH_SHORT).show()
                originalBitmap?.let { src ->
                    lifecycleScope.launch(Dispatchers.Default) {
                        val transparentCutout = BackgroundRemover.removeColorToTransparent(src, sampledColor, tolerance = 40f)
                        withContext(Dispatchers.Main) {
                            currentProcessedBitmap = transparentCutout.copy(Bitmap.Config.ARGB_8888, true)
                            binding.photoCanvas.baseBitmap = currentProcessedBitmap
                        }
                    }
                }
            }
        }

        addSubButton("✨ Jadikan BG Transparan (Hapus Pengganti)") {
            binding.photoCanvas.backgroundColorOverride = null
            binding.photoCanvas.backgroundBitmap = null
            Toast.makeText(this, "Background diatur ke transparan murni (papan catur)", Toast.LENGTH_SHORT).show()
        }

        addSubButton("🖌️ Kuas Pemulih (Restore)") {
            binding.photoCanvas.brushMode = BrushMode.RESTORE
            Toast.makeText(this, "Kuas Pemulih Aktif: Usapkan jari ke bagian orang/baju yang ingin dikembalikan utuh!", Toast.LENGTH_LONG).show()
        }

        addSubButton("🧹 Kuas Hapus (Eraser)") {
            binding.photoCanvas.brushMode = BrushMode.ERASE
            Toast.makeText(this, "Kuas Hapus Aktif: Usapkan jari ke bagian background yang ingin dibersihkan!", Toast.LENGTH_LONG).show()
        }

        addSubButton("✋ Mode Geser (Normal)") {
            binding.photoCanvas.brushMode = BrushMode.NONE
            Toast.makeText(this, "Mode sentuh normal (Geser & stiker)", Toast.LENGTH_SHORT).show()
        }

        addSubButton("⚪ Ganti BG Putih") {
            binding.photoCanvas.backgroundColorOverride = Color.WHITE
            binding.photoCanvas.backgroundBitmap = null
        }

        addSubButton("⚫ Ganti BG Hitam") {
            binding.photoCanvas.backgroundColorOverride = Color.BLACK
            binding.photoCanvas.backgroundBitmap = null
        }

        addSubButton("🟣 Ganti BG Neon") {
            binding.photoCanvas.backgroundColorOverride = Color.parseColor("#8B5CF6")
            binding.photoCanvas.backgroundBitmap = null
        }

        addSubButton("🖼️ Ganti BG dari Galeri") {
            pickBgImageLauncher.launch("image/*")
        }

        addSubButton("🔄 Reset Foto Awal") {
            binding.photoCanvas.brushMode = BrushMode.NONE
            binding.photoCanvas.isEyedropperActive = false
            binding.photoCanvas.isCropModeActive = false
            binding.photoCanvas.backgroundColorOverride = null
            binding.photoCanvas.backgroundBitmap = null
            currentProcessedBitmap = originalBitmap?.copy(Bitmap.Config.ARGB_8888, true)
            binding.photoCanvas.baseBitmap = currentProcessedBitmap
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
        addSubButton("✂️ Tandai Kotak Crop Bebas") {
            binding.photoCanvas.brushMode = BrushMode.NONE
            binding.photoCanvas.isEyedropperActive = false
            binding.photoCanvas.isCropModeActive = true
            Toast.makeText(this, "Geser lingkaran sudut penanda untuk memotong area foto yang diinginkan!", Toast.LENGTH_LONG).show()
        }

        addSubButton("✅ Terapkan Crop") {
            val cropped = binding.photoCanvas.applyVisualCrop()
            if (cropped != null) {
                currentProcessedBitmap = cropped
                Toast.makeText(this, "Foto berhasil dipotong sesuai penanda!", Toast.LENGTH_SHORT).show()
            }
        }

        for (ratio in AspectRatio.values()) {
            addSubButton(ratio.label) {
                binding.photoCanvas.cropAspectRatio = ratio
                if (!binding.photoCanvas.isCropModeActive) {
                    originalBitmap?.let { src ->
                        val cropped = PhotoProcessor.cropToAspectRatio(src, ratio)
                        currentProcessedBitmap = cropped
                        binding.photoCanvas.baseBitmap = cropped
                    }
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
        com.udintpp.mediastudio.ui.dialog.ExportMediaDialog.forPhoto(this) { resolution, format, quality ->
            Toast.makeText(this, "Mengekspor foto ${resolution.label} (${format.displayName})...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val result = PhotoExporter.exportPhoto(
                    context = this@PhotoEditorActivity,
                    baseBitmap = base,
                    stickers = binding.photoCanvas.stickers,
                    resolution = resolution,
                    format = format,
                    quality = quality,
                    backgroundBitmap = binding.photoCanvas.backgroundBitmap,
                    backgroundColorOverride = binding.photoCanvas.backgroundColorOverride
                )
                if (result.isSuccess) {
                    Toast.makeText(this@PhotoEditorActivity, "✅ Berhasil disimpan (${format.extension.uppercase()} ${resolution.label})!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@PhotoEditorActivity, "Gagal mengekspor: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.show()
    }
}
