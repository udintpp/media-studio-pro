package com.udintpp.mediastudio.ui.vfx

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.udintpp.mediastudio.data.model.AeBlendMode
import com.udintpp.mediastudio.data.model.AeConfig
import com.udintpp.mediastudio.data.model.AeEffectType
import com.udintpp.mediastudio.data.model.AeKeyframe
import com.udintpp.mediastudio.data.model.AeMask
import com.udintpp.mediastudio.data.model.AeMaskShape
import com.udintpp.mediastudio.data.model.AeMotionText
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.data.model.VideoExportFormat
import com.udintpp.mediastudio.databinding.ActivityAfterEffectsStudioBinding
import com.udintpp.mediastudio.engine.video.VideoEffectTransformer
import com.udintpp.mediastudio.engine.vfx.AfterEffectsRenderer
import com.udintpp.mediastudio.ui.dialog.ExportMediaDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class AfterEffectsStudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAfterEffectsStudioBinding
    private var mediaUri: Uri? = null
    private var isVideo: Boolean = true

    private var baseBitmap: Bitmap? = null
    private var durationMs: Long = 5000L
    private var currentTimeMs: Long = 0L
    private var isPlaying: Boolean = false
    private var timelineAnimator: ValueAnimator? = null

    private val aeConfig = AeConfig()

    private enum class ActiveVfxSlider {
        GLOW, GLITCH, WIGGLE, LENS_FLARE, GRAIN, LIGHT_RAYS, TURBULENT, ZOOM_BLUR, SABER, ROT_X, ROT_Y, NONE
    }
    private var activeSlider = ActiveVfxSlider.GLOW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAfterEffectsStudioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaUri = intent.data
        if (mediaUri == null) {
            Toast.makeText(this, "Media tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val type = contentResolver.getType(mediaUri!!) ?: ""
        isVideo = !type.startsWith("image/")

        loadMedia(mediaUri!!)
        setupListeners()
    }

    private fun loadMedia(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isVideo) {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(this@AfterEffectsStudioActivity, uri)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val dur = durStr?.toLongOrNull() ?: 5000L
                    durationMs = dur.coerceIn(1000L, 60000L)
                    val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()

                    withContext(Dispatchers.Main) {
                        baseBitmap = frame ?: createFallbackBitmap()
                        initTimeline()
                        updatePreview()
                    }
                } else {
                    val stream = contentResolver.openInputStream(uri)
                    val bmp = BitmapFactory.decodeStream(stream)
                    stream?.close()
                    durationMs = 5000L // 5 second composition for photo VFX

                    withContext(Dispatchers.Main) {
                        baseBitmap = bmp ?: createFallbackBitmap()
                        initTimeline()
                        updatePreview()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    baseBitmap = createFallbackBitmap()
                    initTimeline()
                    updatePreview()
                }
            }
        }
    }

    private fun createFallbackBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(Color.parseColor("#1E293B"))
        val p = android.graphics.Paint().apply {
            color = Color.WHITE
            textSize = 48f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        c.drawText("AFTER EFFECTS COMPOSITION", 640f, 360f, p)
        return bmp
    }

    private fun initTimeline() {
        binding.seekbarTimeline.max = durationMs.toInt()
        binding.seekbarTimeline.progress = 0
        binding.tvTimelineTime.text = "00:00 / ${formatTime(durationMs)}"

        // Add 2 default initial keyframes for demonstration
        aeConfig.keyframes.clear()
        aeConfig.keyframes.add(AeKeyframe(0L, posX = 0f, posY = 0f, scale = 1.0f, rotation = 0f, opacity = 1.0f))
        aeConfig.keyframes.add(AeKeyframe(durationMs, posX = 0f, posY = 0f, scale = 1.05f, rotation = 0f, opacity = 1.0f))
        updateKeyframeCount()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        // Timeline Scrubbing
        binding.seekbarTimeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentTimeMs = progress.toLong()
                    binding.tvTimelineTime.text = "${formatTime(currentTimeMs)} / ${formatTime(durationMs)}"
                    updatePreview()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                pausePlayback()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Play / Pause
        binding.btnPlayPause.setOnClickListener {
            if (isPlaying) {
                pausePlayback()
            } else {
                startPlayback()
            }
        }

        // Keyframe Buttons
        binding.btnKeyframePos.setOnClickListener {
            val cur = aeConfig.keyframes.firstOrNull { it.timeMs == currentTimeMs } ?: AeKeyframe(currentTimeMs)
            aeConfig.keyframes.removeAll { it.timeMs == currentTimeMs }
            aeConfig.keyframes.add(cur.copy(posX = 30f * (if (aeConfig.keyframes.size % 2 == 0) 1 else -1)))
            updateKeyframeCount()
            Toast.makeText(this, "📌 Keyframe Posisi ditambahkan di ${formatTime(currentTimeMs)}", Toast.LENGTH_SHORT).show()
            updatePreview()
        }

        binding.btnKeyframeScale.setOnClickListener {
            val cur = aeConfig.keyframes.firstOrNull { it.timeMs == currentTimeMs } ?: AeKeyframe(currentTimeMs)
            aeConfig.keyframes.removeAll { it.timeMs == currentTimeMs }
            val newScale = if (cur.scale == 1.0f) 1.25f else 1.0f
            aeConfig.keyframes.add(cur.copy(scale = newScale))
            updateKeyframeCount()
            Toast.makeText(this, "🔍 Keyframe Skala ($newScale x) di ${formatTime(currentTimeMs)}", Toast.LENGTH_SHORT).show()
            updatePreview()
        }

        binding.btnKeyframeRot.setOnClickListener {
            val cur = aeConfig.keyframes.firstOrNull { it.timeMs == currentTimeMs } ?: AeKeyframe(currentTimeMs)
            aeConfig.keyframes.removeAll { it.timeMs == currentTimeMs }
            val newRot = (cur.rotation + 15f) % 360f
            aeConfig.keyframes.add(cur.copy(rotation = newRot))
            updateKeyframeCount()
            Toast.makeText(this, "🔄 Keyframe Rotasi (${newRot.toInt()}°) di ${formatTime(currentTimeMs)}", Toast.LENGTH_SHORT).show()
            updatePreview()
        }

        binding.btnKeyframeRotX.setOnClickListener {
            val cur = aeConfig.keyframes.firstOrNull { it.timeMs == currentTimeMs } ?: AeKeyframe(currentTimeMs)
            aeConfig.keyframes.removeAll { it.timeMs == currentTimeMs }
            val newTilt = (cur.rotX + 15f).let { if (it > 45f) -45f else it }
            aeConfig.keyframes.add(cur.copy(rotX = newTilt))
            updateKeyframeCount()
            Toast.makeText(this, "🧊 Keyframe 3D Tilt X (${newTilt.toInt()}°) di ${formatTime(currentTimeMs)}", Toast.LENGTH_SHORT).show()
            updatePreview()
        }

        binding.btnKeyframeRotY.setOnClickListener {
            val cur = aeConfig.keyframes.firstOrNull { it.timeMs == currentTimeMs } ?: AeKeyframe(currentTimeMs)
            aeConfig.keyframes.removeAll { it.timeMs == currentTimeMs }
            val newPan = (cur.rotY + 15f).let { if (it > 45f) -45f else it }
            aeConfig.keyframes.add(cur.copy(rotY = newPan))
            updateKeyframeCount()
            Toast.makeText(this, "🧊 Keyframe 3D Pan Y (${newPan.toInt()}°) di ${formatTime(currentTimeMs)}", Toast.LENGTH_SHORT).show()
            updatePreview()
        }

        binding.btnKeyframeOpacity.setOnClickListener {
            val cur = aeConfig.keyframes.firstOrNull { it.timeMs == currentTimeMs } ?: AeKeyframe(currentTimeMs)
            aeConfig.keyframes.removeAll { it.timeMs == currentTimeMs }
            val newOp = if (cur.opacity >= 1.0f) 0.5f else 1.0f
            aeConfig.keyframes.add(cur.copy(opacity = newOp))
            updateKeyframeCount()
            Toast.makeText(this, "👁️ Keyframe Opasitas (${(newOp * 100).toInt()}%) di ${formatTime(currentTimeMs)}", Toast.LENGTH_SHORT).show()
            updatePreview()
        }

        binding.btnKeyframeEase.setOnClickListener {
            showEasingDialog()
        }

        binding.btnKeyframeClear.setOnClickListener {
            aeConfig.keyframes.clear()
            updateKeyframeCount()
            Toast.makeText(this, "Semua keyframe dihapus", Toast.LENGTH_SHORT).show()
            updatePreview()
        }

        // VFX Deck Buttons
        binding.btnVfx3dLayer.setOnClickListener {
            aeConfig.is3DLayerEnabled = !aeConfig.is3DLayerEnabled
            binding.btnVfx3dLayer.text = if (aeConfig.is3DLayerEnabled) "🧊 3D: ON" else "🧊 3D Layer Space"
            if (aeConfig.is3DLayerEnabled) {
                activeSlider = ActiveVfxSlider.ROT_Y
                updateSliderUI("Rotasi 3D Space (Pan Y)", 50)
            }
            updateBadge()
            updatePreview()
            Toast.makeText(this, if (aeConfig.is3DLayerEnabled) "3D Layer Space Aktif" else "3D Layer Nonaktif", Toast.LENGTH_SHORT).show()
        }

        binding.btnVfxMask.setOnClickListener {
            showMaskingDialog()
        }

        binding.btnVfxSpeed.setOnClickListener {
            showSpeedRampingDialog()
        }

        binding.btnVfxGlow.setOnClickListener {
            aeConfig.isOpticalGlowEnabled = !aeConfig.isOpticalGlowEnabled
            activeSlider = ActiveVfxSlider.GLOW
            updateSliderUI("Intensitas Optical Deep Glow", (aeConfig.glowIntensity * 100f).toInt())
            binding.btnVfxGlow.text = if (aeConfig.isOpticalGlowEnabled) "🌟 Glow: ON" else "🌟 Optical Glow"
            updateBadge()
            updatePreview()
        }

        binding.btnVfxGlitch.setOnClickListener {
            aeConfig.isRgbGlitchEnabled = !aeConfig.isRgbGlitchEnabled
            activeSlider = ActiveVfxSlider.GLITCH
            updateSliderUI("Intensitas RGB Split & Glitch", (aeConfig.glitchIntensity * 100f).toInt())
            binding.btnVfxGlitch.text = if (aeConfig.isRgbGlitchEnabled) "⚡ Glitch: ON" else "⚡ RGB Glitch"
            updateBadge()
            updatePreview()
        }

        binding.btnVfxWiggle.setOnClickListener {
            aeConfig.isWiggleEnabled = !aeConfig.isWiggleEnabled
            activeSlider = ActiveVfxSlider.WIGGLE
            updateSliderUI("Amplitudo Wiggle Shake (px)", aeConfig.wiggleAmplitude.toInt())
            binding.btnVfxWiggle.text = if (aeConfig.isWiggleEnabled) "📳 Wiggle: ON" else "📳 Camera Wiggle"
            updateBadge()
            updatePreview()
        }

        binding.btnVfxLightRays.setOnClickListener {
            aeConfig.isLightRaysEnabled = !aeConfig.isLightRaysEnabled
            activeSlider = ActiveVfxSlider.LIGHT_RAYS
            updateSliderUI("Intensitas CC Light Rays (God Rays)", (aeConfig.lightRaysIntensity * 100f).toInt())
            binding.btnVfxLightRays.text = if (aeConfig.isLightRaysEnabled) "☀️ Light Rays: ON" else "☀️ CC Light Rays"
            updateBadge()
            updatePreview()
        }

        binding.btnVfxTurbulent.setOnClickListener {
            aeConfig.isTurbulentDisplaceEnabled = !aeConfig.isTurbulentDisplaceEnabled
            activeSlider = ActiveVfxSlider.TURBULENT
            updateSliderUI("Besaran Displace Liquify (px)", aeConfig.turbulentAmount.toInt())
            binding.btnVfxTurbulent.text = if (aeConfig.isTurbulentDisplaceEnabled) "🌊 Turbulent: ON" else "🌊 Turbulent Displace"
            updateBadge()
            updatePreview()
        }

        binding.btnVfxZoomBlur.setOnClickListener {
            aeConfig.isZoomBlurEnabled = !aeConfig.isZoomBlurEnabled
            activeSlider = ActiveVfxSlider.ZOOM_BLUR
            updateSliderUI("Intensitas Radial Zoom Blur", aeConfig.zoomBlurAmount.toInt())
            binding.btnVfxZoomBlur.text = if (aeConfig.isZoomBlurEnabled) "🌀 Zoom Blur: ON" else "🌀 CC Radial Zoom"
            updateBadge()
            updatePreview()
        }

        binding.btnVfxSaber.setOnClickListener {
            aeConfig.isSaberEdgeEnabled = !aeConfig.isSaberEdgeEnabled
            activeSlider = ActiveVfxSlider.SABER
            updateSliderUI("Ketebalan Saber Neon Edge", (aeConfig.saberWidth * 5f).toInt())
            binding.btnVfxSaber.text = if (aeConfig.isSaberEdgeEnabled) "⚡ Saber: ON" else "⚡ Saber Neon Edge"
            updateBadge()
            updatePreview()
        }

        binding.btnVfxLensFlare.setOnClickListener {
            aeConfig.isLensFlareEnabled = !aeConfig.isLensFlareEnabled
            activeSlider = ActiveVfxSlider.LENS_FLARE
            updateSliderUI("Intensitas Anamorphic Lens Flare", (aeConfig.flareIntensity * 100f).toInt())
            binding.btnVfxLensFlare.text = if (aeConfig.isLensFlareEnabled) "☀️ Flare: ON" else "☀️ Lens Flare"
            updateBadge()
            updatePreview()
        }

        binding.btnVfxLumetri.setOnClickListener {
            showLumetriDialog()
        }

        binding.btnVfxParticles.setOnClickListener {
            showParticleSelectionDialog()
        }

        binding.btnVfxFilmGrain.setOnClickListener {
            aeConfig.isFilmGrainEnabled = !aeConfig.isFilmGrainEnabled
            activeSlider = ActiveVfxSlider.GRAIN
            updateSliderUI("Tekstur Film Grain 35mm", (aeConfig.grainAmount * 100f).toInt())
            binding.btnVfxFilmGrain.text = if (aeConfig.isFilmGrainEnabled) "🎞️ Grain: ON" else "🎞️ Film Grain 35mm"
            updateBadge()
            updatePreview()
        }

        binding.btnVfxMotionText.setOnClickListener {
            showMotionTextDialog()
        }

        binding.btnVfxAudioSpectrum.setOnClickListener {
            aeConfig.isAudioSpectrumEnabled = !aeConfig.isAudioSpectrumEnabled
            binding.btnVfxAudioSpectrum.text = if (aeConfig.isAudioSpectrumEnabled) "🎵 Waveform: ON" else "🎵 Audio Waveform"
            updateBadge()
            updatePreview()
        }

        binding.btnVfxBlendMode.setOnClickListener {
            showBlendModeDialog()
        }

        // Active Slider listener
        binding.sliderVfx.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvVfxToolValue.text = "${value.toInt()}%"
                when (activeSlider) {
                    ActiveVfxSlider.GLOW -> aeConfig.glowIntensity = value / 100f
                    ActiveVfxSlider.GLITCH -> aeConfig.glitchIntensity = value / 100f
                    ActiveVfxSlider.WIGGLE -> aeConfig.wiggleAmplitude = value
                    ActiveVfxSlider.LENS_FLARE -> aeConfig.flareIntensity = value / 100f
                    ActiveVfxSlider.GRAIN -> aeConfig.grainAmount = value / 100f
                    ActiveVfxSlider.LIGHT_RAYS -> aeConfig.lightRaysIntensity = value / 100f
                    ActiveVfxSlider.TURBULENT -> aeConfig.turbulentAmount = (value / 100f) * 40f
                    ActiveVfxSlider.ZOOM_BLUR -> aeConfig.zoomBlurAmount = (value / 100f) * 50f
                    ActiveVfxSlider.SABER -> aeConfig.saberWidth = (value / 100f) * 20f + 2f
                    ActiveVfxSlider.ROT_X -> aeConfig.rotateX = (value - 50f) * 1.2f
                    ActiveVfxSlider.ROT_Y -> aeConfig.rotateY = (value - 50f) * 1.2f
                    ActiveVfxSlider.NONE -> {}
                }
                updatePreview()
            }
        }

        // Export Button
        binding.btnExportVfx.setOnClickListener {
            showExportVfxDialog()
        }
    }

    private fun updateSliderUI(name: String, value: Int) {
        binding.tvVfxToolName.text = name
        binding.tvVfxToolValue.text = "$value%"
        binding.sliderVfx.value = value.toFloat().coerceIn(0f, 100f)
    }

    private fun updateBadge() {
        val activeCount = listOf(
            aeConfig.is3DLayerEnabled,
            aeConfig.mask.isEnabled,
            aeConfig.isOpticalGlowEnabled,
            aeConfig.isRgbGlitchEnabled,
            aeConfig.isWiggleEnabled,
            aeConfig.isLensFlareEnabled,
            aeConfig.isLightRaysEnabled,
            aeConfig.isTurbulentDisplaceEnabled,
            aeConfig.isZoomBlurEnabled,
            aeConfig.isSaberEdgeEnabled,
            aeConfig.lumetriPreset != "NONE",
            aeConfig.activeParticleType != null,
            aeConfig.isFilmGrainEnabled,
            aeConfig.isAudioSpectrumEnabled,
            aeConfig.motionTexts.isNotEmpty()
        ).count { it }
        binding.tvActiveVfxBadge.text = "⚡ AE Engine: $activeCount Efek Aktif"
    }

    private fun updateKeyframeCount() {
        binding.tvKeyframeCount.text = "${aeConfig.keyframes.size} Keyframes"
    }

    private fun showEasingDialog() {
        val easingNames = arrayOf("Linear", "Easy Ease (Hermite)", "Ease In (Accelerate)", "Ease Out (Decelerate)", "Bounce (Pegas)", "Elastic (Osilasi)")
        val easingCodes = arrayOf("LINEAR", "EASY_EASE", "EASE_IN", "EASE_OUT", "BOUNCE", "ELASTIC")

        AlertDialog.Builder(this)
            .setTitle("Pilih Kurva Easing Keyframe")
            .setItems(easingNames) { _, which ->
                val chosen = easingCodes[which]
                val cur = aeConfig.keyframes.firstOrNull { it.timeMs == currentTimeMs }
                if (cur != null) {
                    aeConfig.keyframes.removeAll { it.timeMs == currentTimeMs }
                    aeConfig.keyframes.add(cur.copy(easeType = chosen))
                } else if (aeConfig.keyframes.isNotEmpty()) {
                    val last = aeConfig.keyframes.last()
                    aeConfig.keyframes[aeConfig.keyframes.size - 1] = last.copy(easeType = chosen)
                }
                binding.btnKeyframeEase.text = "📈 ${easingNames[which]}"
                Toast.makeText(this, "Kurva Easing diubah ke: ${easingNames[which]}", Toast.LENGTH_SHORT).show()
                updatePreview()
            }
            .show()
    }

    private fun showMaskingDialog() {
        val options = arrayOf(
            if (aeConfig.mask.isEnabled) "Matikan Masking" else "Aktifkan Masking",
            "Bentuk: Kotak Persegi (Rectangle)",
            "Bentuk: Lingkaran Oval (Ellipse)",
            if (aeConfig.mask.isInverted) "Invert Mask: ON (Area Luar)" else "Invert Mask: OFF (Area Dalam)",
            "Halus Mask Feather: 10px",
            "Halus Mask Feather: 30px"
        )
        AlertDialog.Builder(this)
            .setTitle("Pengaturan Masking & Rotoscoping")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> aeConfig.mask.isEnabled = !aeConfig.mask.isEnabled
                    1 -> {
                        aeConfig.mask.isEnabled = true
                        aeConfig.mask.shape = AeMaskShape.RECTANGLE
                    }
                    2 -> {
                        aeConfig.mask.isEnabled = true
                        aeConfig.mask.shape = AeMaskShape.ELLIPSE
                    }
                    3 -> {
                        aeConfig.mask.isEnabled = true
                        aeConfig.mask.isInverted = !aeConfig.mask.isInverted
                    }
                    4 -> {
                        aeConfig.mask.isEnabled = true
                        aeConfig.mask.feather = 10f
                    }
                    5 -> {
                        aeConfig.mask.isEnabled = true
                        aeConfig.mask.feather = 30f
                    }
                }
                binding.btnVfxMask.text = if (aeConfig.mask.isEnabled) "🎭 Mask: ON" else "🎭 Masking & Roto"
                updateBadge()
                updatePreview()
            }
            .show()
    }

    private fun showSpeedRampingDialog() {
        val speeds = arrayOf("0.25x (Super Slow-Mo)", "0.5x (Slow Motion Sinematik)", "1.0x (Normal)", "1.5x (Cepat)", "2.0x (Time-Lapse / Hyperlapse)", "Putar Mundur (Reverse Time)")
        AlertDialog.Builder(this)
            .setTitle("Speed Ramping & Time Remapping")
            .setItems(speeds) { _, which ->
                when (which) {
                    0 -> aeConfig.speedMultiplier = 0.25f
                    1 -> aeConfig.speedMultiplier = 0.5f
                    2 -> {
                        aeConfig.speedMultiplier = 1.0f
                        aeConfig.isTimeReversed = false
                    }
                    3 -> aeConfig.speedMultiplier = 1.5f
                    4 -> aeConfig.speedMultiplier = 2.0f
                    5 -> aeConfig.isTimeReversed = !aeConfig.isTimeReversed
                }
                binding.btnVfxSpeed.text = if (aeConfig.isTimeReversed) "⚡ Speed: Reverse" else "⚡ Speed: ${aeConfig.speedMultiplier}x"
                Toast.makeText(this, "Kecepatan timeline diatur: ${speeds[which]}", Toast.LENGTH_SHORT).show()
                updatePreview()
            }
            .show()
    }

    private fun showLumetriDialog() {
        val presets = arrayOf(
            "Original / Tanpa Grading",
            "🎬 Sinematik Teal & Orange (Hollywood)",
            "🌆 Cyberpunk Neon (Blade Runner)",
            "🎞️ Film Noir (Klasik B&W)",
            "🟢 Matrix Emerald (Sci-Fi Code Green)",
            "🌅 Vintage Warm (Golden Hour)"
        )
        val codes = arrayOf("NONE", "TEAL_ORANGE", "CYBERPUNK", "FILM_NOIR", "MATRIX", "VINTAGE_WARM")

        AlertDialog.Builder(this)
            .setTitle("Lumetri Color Grading Curves")
            .setItems(presets) { _, which ->
                aeConfig.lumetriPreset = codes[which]
                binding.btnVfxLumetri.text = if (aeConfig.lumetriPreset != "NONE") "🎨 Lumetri: ${codes[which]}" else "🎨 Lumetri Color"
                updateBadge()
                updatePreview()
            }
            .show()
    }

    private fun showParticleSelectionDialog() {
        val items = arrayOf(
            "Matikan Partikel (None)",
            "✨ Sparks & Embers (Percikan Api)",
            "🌧️ Rain & Mist (Hujan Sinematik)",
            "❄️ Snow Drift (Butiran Salju)",
            "🔮 Bokeh Orbs (Cahaya Aperture)"
        )
        AlertDialog.Builder(this)
            .setTitle("Pilih Simulasi Partikel (Particle World)")
            .setItems(items) { _, which ->
                aeConfig.activeParticleType = when (which) {
                    1 -> AeEffectType.PARTICLE_SPARKS
                    2 -> AeEffectType.PARTICLE_RAIN
                    3 -> AeEffectType.PARTICLE_SNOW
                    4 -> AeEffectType.PARTICLE_BOKEH
                    else -> null
                }
                binding.btnVfxParticles.text = if (aeConfig.activeParticleType != null) "✨ Partikel: ON" else "✨ Partikel Sparks/Rain"
                updateBadge()
                updatePreview()
            }
            .show()
    }

    private fun showMotionTextDialog() {
        val styles = arrayOf("Mengetik (Typewriter)", "Kinetic Zoom Pop", "Neon Pulse Glow", "Cyberpunk Glitch")
        val styleCodes = arrayOf("TYPEWRITER", "KINETIC_ZOOM", "NEON_PULSE", "GLITCH")

        val input = android.widget.EditText(this).apply {
            hint = "Tulis judul After Effects..."
            setText("AFTER EFFECTS PRO")
        }

        AlertDialog.Builder(this)
            .setTitle("Tambah Motion Graphics Text")
            .setView(input)
            .setItems(styles) { _, which ->
                val txt = input.text.toString().ifBlank { "AFTER EFFECTS" }
                aeConfig.motionTexts.clear()
                aeConfig.motionTexts.add(
                    AeMotionText(
                        text = txt,
                        style = styleCodes[which],
                        color = Color.WHITE,
                        glowColor = Color.parseColor("#06B6D4")
                    )
                )
                binding.btnVfxMotionText.text = "🔤 Motion: ${styleCodes[which]}"
                updateBadge()
                updatePreview()
            }
            .setNegativeButton("Hapus Text") { _, _ ->
                aeConfig.motionTexts.clear()
                binding.btnVfxMotionText.text = "🔤 Motion Text"
                updateBadge()
                updatePreview()
            }
            .show()
    }

    private fun showBlendModeDialog() {
        val modes = AeBlendMode.values()
        AlertDialog.Builder(this)
            .setTitle("Pilih Layer Blending Mode")
            .setItems(modes.map { it.displayName }.toTypedArray()) { _, which ->
                aeConfig.blendMode = modes[which]
                binding.btnVfxBlendMode.text = "🎭 Blend: ${modes[which].displayName}"
                Toast.makeText(this, "Blending Mode: ${modes[which].displayName}", Toast.LENGTH_SHORT).show()
                updatePreview()
            }
            .show()
    }

    private fun updatePreview() {
        val base = baseBitmap ?: return
        val rendered = AfterEffectsRenderer.renderFrame(base, currentTimeMs, aeConfig)
        binding.ivVfxPreview.setImageBitmap(rendered)
    }

    private fun startPlayback() {
        isPlaying = true
        binding.btnPlayPause.setImageResource(com.udintpp.mediastudio.R.drawable.ic_pause)

        timelineAnimator?.cancel()
        timelineAnimator = ValueAnimator.ofInt(currentTimeMs.toInt(), durationMs.toInt()).apply {
            duration = durationMs - currentTimeMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Int
                currentTimeMs = p.toLong()
                binding.seekbarTimeline.progress = p
                binding.tvTimelineTime.text = "${formatTime(currentTimeMs)} / ${formatTime(durationMs)}"
                updatePreview()
            }
            start()
        }
    }

    private fun pausePlayback() {
        isPlaying = false
        binding.btnPlayPause.setImageResource(com.udintpp.mediastudio.R.drawable.ic_play)
        timelineAnimator?.cancel()
        timelineAnimator = null
    }

    private fun showExportVfxDialog() {
        pausePlayback()
        ExportMediaDialog.forVideo(this) { resolution, format ->
            startExportVfx(resolution, format)
        }.show()
    }

    private fun startExportVfx(resolution: ExportResolution, format: VideoExportFormat) {
        val uri = mediaUri ?: return
        val base = baseBitmap ?: return

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MediaStudioPro_VFX")
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = File(outputDir, "MediaStudio_AfterEffects_${resolution.name}_$timeStamp.${format.extension}")

        binding.layoutVfxExportProgress.visibility = View.VISIBLE
        binding.tvVfxProgressPercent.text = "Mengekspor VFX ${resolution.label} (${format.displayName})..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // If format is GIF or image slideshow, render frame sequence
                if (format == VideoExportFormat.GIF) {
                    val totalFrames = 30
                    val frameList = mutableListOf<Bitmap>()
                    val stepMs = durationMs / totalFrames

                    for (i in 0 until totalFrames) {
                        val t = i * stepMs
                        val rendered = AfterEffectsRenderer.renderFrame(base, t, aeConfig)
                        val scaled = if (resolution.width > 0 && resolution.width < 1000) {
                            val sc = resolution.width.toFloat() / rendered.width
                            Bitmap.createScaledBitmap(rendered, resolution.width, (rendered.height * sc).toInt(), true)
                        } else {
                            rendered
                        }
                        frameList.add(scaled)
                        withContext(Dispatchers.Main) {
                            binding.tvVfxProgressPercent.text = "Rendering frame ${i + 1}/$totalFrames..."
                        }
                    }

                    val fos = FileOutputStream(outputFile)
                    com.udintpp.mediastudio.engine.video.VideoChromaKeyEngine.writeAnimatedGif(frameList, delayMs = stepMs.toInt(), out = fos)
                    fos.flush()
                    fos.close()

                    withContext(Dispatchers.Main) {
                        binding.layoutVfxExportProgress.visibility = View.GONE
                        Toast.makeText(this@AfterEffectsStudioActivity, "✅ Animasi VFX After Effects selesai diexport!\nDisimpan di: ${outputFile.name}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // Export video format with hardware transformer
                    withContext(Dispatchers.Main) {
                        VideoEffectTransformer.transformVideo(
                            context = this@AfterEffectsStudioActivity,
                            inputUri = uri,
                            outputFile = outputFile,
                            resolution = resolution,
                            exportFormat = format
                        ).collectLatest { state ->
                            when (state) {
                                is VideoEffectTransformer.TransformState.Progress -> {
                                    binding.tvVfxProgressPercent.text = "Exporting VFX: ${state.percentage}%"
                                }
                                is VideoEffectTransformer.TransformState.Success -> {
                                    binding.layoutVfxExportProgress.visibility = View.GONE
                                    Toast.makeText(this@AfterEffectsStudioActivity, "✅ Video VFX After Effects ${resolution.label} selesai diexport!\nDisimpan di: ${outputFile.name}", Toast.LENGTH_LONG).show()
                                }
                                is VideoEffectTransformer.TransformState.Error -> {
                                    binding.layoutVfxExportProgress.visibility = View.GONE
                                    Toast.makeText(this@AfterEffectsStudioActivity, "Gagal export: ${state.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.layoutVfxExportProgress.visibility = View.GONE
                    Toast.makeText(this@AfterEffectsStudioActivity, "Gagal export: ${e.message}", Toast.LENGTH_LONG).show()
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
        pausePlayback()
    }
}
