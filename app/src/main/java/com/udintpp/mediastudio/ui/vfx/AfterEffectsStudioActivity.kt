package com.udintpp.mediastudio.ui.vfx

import android.animation.ValueAnimator
import android.annotation.SuppressLint
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
import android.widget.EditText
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
import com.udintpp.mediastudio.data.model.AeSticker
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

    private enum class DragTarget {
        MOTION_TEXT, STICKER
    }
    private var activeDragTarget = DragTarget.MOTION_TEXT

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

        binding.btnCompSettings.setOnClickListener {
            showCompSettingsDialog()
        }

        binding.btnVfxParticleSettings.setOnClickListener {
            showParticleSettingsDialog()
        }

        binding.btnVfxTextStyle.setOnClickListener {
            showTextStyleDialog()
        }

        binding.btnVfxSticker.setOnClickListener {
            showStickerDialog()
        }

        setupTouchRepos()

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
            aeConfig.motionTexts.isNotEmpty(),
            aeConfig.stickers.isNotEmpty()
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
        activeDragTarget = DragTarget.MOTION_TEXT
        val styles = arrayOf(
            "Mengetik (Typewriter)",
            "Kinetic Zoom Pop",
            "Neon Pulse Glow",
            "Cyberpunk Glitch",
            "3D Space Flip",
            "Wave Bounce (Gelombang Pantul)",
            "Fade & Slide (Muncul Meluncur)"
        )
        val styleCodes = arrayOf("TYPEWRITER", "KINETIC_ZOOM", "NEON_PULSE", "GLITCH", "3D_FLIP", "WAVE_BOUNCE", "FADE_SLIDE")

        val currentText = aeConfig.motionTexts.lastOrNull()?.text ?: "AFTER EFFECTS PRO"
        val input = EditText(this).apply {
            hint = "Tulis judul After Effects..."
            setText(currentText)
        }

        AlertDialog.Builder(this)
            .setTitle("Tambah / Ubah Motion Graphics Text")
            .setView(input)
            .setItems(styles) { _, which ->
                val txt = input.text.toString().ifBlank { "AFTER EFFECTS" }
                val existing = aeConfig.motionTexts.lastOrNull()
                if (existing != null) {
                    existing.text = txt
                    existing.style = styleCodes[which]
                } else {
                    aeConfig.motionTexts.add(
                        AeMotionText(
                            text = txt,
                            style = styleCodes[which],
                            color = Color.WHITE,
                            glowColor = Color.parseColor("#06B6D4")
                        )
                    )
                }
                binding.btnVfxMotionText.text = "🔤 Motion: ${styleCodes[which]}"
                Toast.makeText(this, "Motion text: ${styleCodes[which]}. Geser jari di preview untuk reposisi!", Toast.LENGTH_SHORT).show()
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

    private fun showCompSettingsDialog() {
        val options = arrayOf(
            "⏱️ Durasi Komposisi (${formatTime(durationMs)})",
            "📐 Rasio Aspek Layar (${aeConfig.compAspectRatio})",
            "📺 Resolusi Komposisi (${aeConfig.compWidth} x ${aeConfig.compHeight})"
        )
        AlertDialog.Builder(this)
            .setTitle("⚙️ Pengaturan Komposisi (Ctrl+K)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showDurationPickerDialog()
                    1 -> showAspectRatioPickerDialog()
                    2 -> showResolutionPickerDialog()
                }
            }
            .show()
    }

    private fun showDurationPickerDialog() {
        val presets = arrayOf("3 Detik", "5 Detik", "10 Detik", "15 Detik", "30 Detik", "60 Detik", "Durasi Kustom (Ketik Detik)")
        val durations = longArrayOf(3000L, 5000L, 10000L, 15000L, 30000L, 60000L, -1L)

        AlertDialog.Builder(this)
            .setTitle("Pilih Durasi Komposisi")
            .setItems(presets) { _, which ->
                if (durations[which] == -1L) {
                    val input = EditText(this).apply {
                        hint = "Masukkan durasi (detik)"
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        setText("${durationMs / 1000}")
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Durasi Kustom (Detik)")
                        .setView(input)
                        .setPositiveButton("Simpan") { _, _ ->
                            val sec = input.text.toString().toLongOrNull() ?: 5L
                            setCompositionDuration(sec.coerceIn(1L, 300L) * 1000L)
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                } else {
                    setCompositionDuration(durations[which])
                }
            }
            .show()
    }

    private fun setCompositionDuration(newDurMs: Long) {
        durationMs = newDurMs
        aeConfig.compDurationMs = newDurMs
        binding.seekbarTimeline.max = durationMs.toInt()
        currentTimeMs = currentTimeMs.coerceAtMost(durationMs)
        binding.seekbarTimeline.progress = currentTimeMs.toInt()
        binding.tvTimelineTime.text = "${formatTime(currentTimeMs)} / ${formatTime(durationMs)}"
        Toast.makeText(this, "Durasi komposisi diubah: ${formatTime(durationMs)}", Toast.LENGTH_SHORT).show()
        updatePreview()
    }

    private fun showAspectRatioPickerDialog() {
        val ratios = arrayOf(
            "16:9 Landscape (YouTube / TV Sinematik)",
            "9:16 Vertikal (TikTok / IG Reels / Shorts)",
            "1:1 Persegi (Instagram Feed)",
            "4:5 Sinematik Portrait (Social Post)",
            "21:9 Ultrawide Sinematik Layar Bioskop"
        )
        val ratioCodes = arrayOf("16:9", "9:16", "1:1", "4:5", "21:9")

        AlertDialog.Builder(this)
            .setTitle("Pilih Rasio Aspek (Aspect Ratio)")
            .setItems(ratios) { _, which ->
                aeConfig.compAspectRatio = ratioCodes[which]
                when (ratioCodes[which]) {
                    "16:9" -> { aeConfig.compWidth = 1920; aeConfig.compHeight = 1080 }
                    "9:16" -> { aeConfig.compWidth = 1080; aeConfig.compHeight = 1920 }
                    "1:1" -> { aeConfig.compWidth = 1080; aeConfig.compHeight = 1080 }
                    "4:5" -> { aeConfig.compWidth = 1080; aeConfig.compHeight = 1350 }
                    "21:9" -> { aeConfig.compWidth = 2560; aeConfig.compHeight = 1080 }
                }
                Toast.makeText(this, "Rasio diubah: ${ratioCodes[which]} (${aeConfig.compWidth}x${aeConfig.compHeight})", Toast.LENGTH_SHORT).show()
                updatePreview()
            }
            .show()
    }

    private fun showResolutionPickerDialog() {
        val resOptions = arrayOf(
            "4K UHD (3840 x 2160) - Ultra High Definition",
            "2K QHD (2560 x 1440) - Quad HD",
            "Full HD 1080p (1920 x 1080) - Standard Pro",
            "HD 720p (1280 x 720) - Ringan",
            "Kustom Resolusi (Input Manual Lebar x Tinggi)"
        )
        AlertDialog.Builder(this)
            .setTitle("Pilih Resolusi Komposisi")
            .setItems(resOptions) { _, which ->
                when (which) {
                    0 -> { aeConfig.compWidth = 3840; aeConfig.compHeight = 2160 }
                    1 -> { aeConfig.compWidth = 2560; aeConfig.compHeight = 1440 }
                    2 -> { aeConfig.compWidth = 1920; aeConfig.compHeight = 1080 }
                    3 -> { aeConfig.compWidth = 1280; aeConfig.compHeight = 720 }
                    4 -> {
                        val layout = android.widget.LinearLayout(this).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setPadding(50, 20, 50, 10)
                        }
                        val etW = EditText(this).apply {
                            hint = "Lebar (Width) px"
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER
                            setText("${aeConfig.compWidth}")
                        }
                        val etH = EditText(this).apply {
                            hint = "Tinggi (Height) px"
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER
                            setText("${aeConfig.compHeight}")
                        }
                        layout.addView(etW)
                        layout.addView(etH)
                        AlertDialog.Builder(this)
                            .setTitle("Kustom Resolusi (px)")
                            .setView(layout)
                            .setPositiveButton("Simpan") { _, _ ->
                                val w = etW.text.toString().toIntOrNull() ?: 1920
                                val h = etH.text.toString().toIntOrNull() ?: 1080
                                aeConfig.compWidth = w.coerceIn(320, 7680)
                                aeConfig.compHeight = h.coerceIn(240, 4320)
                                Toast.makeText(this, "Resolusi diatur: ${aeConfig.compWidth} x ${aeConfig.compHeight}", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .setNegativeButton("Batal", null)
                            .show()
                        return@setItems
                    }
                }
                Toast.makeText(this, "Resolusi diatur: ${aeConfig.compWidth} x ${aeConfig.compHeight}", Toast.LENGTH_SHORT).show()
                updatePreview()
            }
            .show()
    }

    private fun showParticleSettingsDialog() {
        val options = arrayOf(
            "⚡ Kecepatan Efek Global (${aeConfig.effectSpeed}x)",
            "✨ Jumlah Partikel / Densitas (${aeConfig.particleCount})",
            "🚀 Kecepatan Gerak Partikel (${aeConfig.particleSpeed}x)",
            "📏 Ukuran Butiran Partikel (${aeConfig.particleSize}x)"
        )
        AlertDialog.Builder(this)
            .setTitle("🎛️ Parameter Partikel & Kecepatan Efek")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val speeds = arrayOf("0.25x (Sangat Lambat)", "0.5x (Lambat)", "1.0x (Normal)", "1.5x (Cepat)", "2.0x (Sangat Cepat)", "3.0x (Hiper)")
                        val speedVals = floatArrayOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f)
                        AlertDialog.Builder(this)
                            .setTitle("Kecepatan Efek Global")
                            .setItems(speeds) { _, sIdx ->
                                aeConfig.effectSpeed = speedVals[sIdx]
                                Toast.makeText(this, "Kecepatan Efek: ${speedVals[sIdx]}x", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    1 -> {
                        val counts = arrayOf("15 Partikel (Minimalis)", "35 Partikel (Ringan)", "50 Partikel (Normal)", "100 Partikel (Padat)", "150 Partikel (Intensif)", "200 Partikel (Badai)")
                        val countVals = intArrayOf(15, 35, 50, 100, 150, 200)
                        AlertDialog.Builder(this)
                            .setTitle("Jumlah Partikel (Density Count)")
                            .setItems(counts) { _, cIdx ->
                                aeConfig.particleCount = countVals[cIdx]
                                Toast.makeText(this, "Jumlah partikel: ${countVals[cIdx]}", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    2 -> {
                        val speeds = arrayOf("0.2x (Super Slow)", "0.5x (Lambat)", "1.0x (Normal)", "1.5x (Cepat)", "2.5x (Kilat)", "3.0x (Maksimal)")
                        val speedVals = floatArrayOf(0.2f, 0.5f, 1.0f, 1.5f, 2.5f, 3.0f)
                        AlertDialog.Builder(this)
                            .setTitle("Kecepatan Partikel (Velocity)")
                            .setItems(speeds) { _, spIdx ->
                                aeConfig.particleSpeed = speedVals[spIdx]
                                Toast.makeText(this, "Kecepatan partikel: ${speedVals[spIdx]}x", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    3 -> {
                        val sizes = arrayOf("0.5x (Kecil Halus)", "1.0x (Normal)", "1.5x (Besar)", "2.0x (Tebal Jelas)")
                        val sizeVals = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
                        AlertDialog.Builder(this)
                            .setTitle("Ukuran Butiran Partikel")
                            .setItems(sizes) { _, szIdx ->
                                aeConfig.particleSize = sizeVals[szIdx]
                                Toast.makeText(this, "Ukuran partikel: ${sizeVals[szIdx]}x", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showTextStyleDialog() {
        activeDragTarget = DragTarget.MOTION_TEXT
        val currentText = aeConfig.motionTexts.lastOrNull()
        val embossStatus = if (currentText?.isEmbossEnabled == true) "ON (Aktif)" else "OFF"
        val options = arrayOf(
            "🧊 Huruf Timbul 3D (Bevel & Extrusion): $embossStatus",
            "📐 Kedalaman Timbul 3D (${currentText?.embossDepth?.toInt() ?: 6}px)",
            "🔤 Pilihan Font Typography (${currentText?.fontFamily ?: "DEFAULT_BOLD"})",
            "🎨 Pilihan Warna Huruf",
            "📏 Ukuran Font Text (${currentText?.fontSize?.toInt() ?: 44}sp)",
            "📍 Atur Posisi Text Preset",
            "💡 Info: Sentuh & Geser jari di layar preview untuk posisi bebas"
        )

        AlertDialog.Builder(this)
            .setTitle("✨ Kustomisasi Huruf Timbul 3D & Font")
            .setItems(options) { _, which ->
                val target = aeConfig.motionTexts.lastOrNull() ?: run {
                    val newText = AeMotionText(text = "AFTER EFFECTS PRO", color = Color.WHITE)
                    aeConfig.motionTexts.add(newText)
                    newText
                }

                when (which) {
                    0 -> {
                        target.isEmbossEnabled = !target.isEmbossEnabled
                        Toast.makeText(this, if (target.isEmbossEnabled) "Huruf Timbul 3D: ON" else "Huruf Timbul 3D: OFF", Toast.LENGTH_SHORT).show()
                        updateBadge()
                        updatePreview()
                    }
                    1 -> {
                        val depths = arrayOf("Halus (3px)", "Sedang (6px)", "Tebal Sinematik (12px)", "Ekstrim 3D Bevel (20px)")
                        val depthVals = floatArrayOf(3f, 6f, 12f, 20f)
                        AlertDialog.Builder(this)
                            .setTitle("Kedalaman Timbul 3D")
                            .setItems(depths) { _, dIdx ->
                                target.embossDepth = depthVals[dIdx]
                                target.isEmbossEnabled = true
                                updatePreview()
                            }
                            .show()
                    }
                    2 -> {
                        val fontNames = arrayOf("Sans-Serif Bold (Modern)", "Monospace (Hacker/Cyber)", "Serif (Sinematik/Klasik)", "Casual (Tulisan Tangan)", "Default Bold")
                        val fontCodes = arrayOf("SANS_SERIF", "MONOSPACE", "SERIF", "CASUAL", "DEFAULT_BOLD")
                        AlertDialog.Builder(this)
                            .setTitle("Pilih Font Typography")
                            .setItems(fontNames) { _, fIdx ->
                                target.fontFamily = fontCodes[fIdx]
                                Toast.makeText(this, "Font diubah: ${fontNames[fIdx]}", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    3 -> {
                        val colorNames = arrayOf("Putih Murni", "Emas Kemilau", "Cyan Neon", "Pink Magenta", "Hijau Limau Neon", "Merah Crimson", "Ungu Neon", "Hitam Gelap")
                        val colorVals = intArrayOf(
                            Color.WHITE,
                            Color.parseColor("#FBBF24"),
                            Color.parseColor("#06B6D4"),
                            Color.parseColor("#EC4899"),
                            Color.parseColor("#84CC16"),
                            Color.parseColor("#EF4444"),
                            Color.parseColor("#A855F7"),
                            Color.parseColor("#0F172A")
                        )
                        AlertDialog.Builder(this)
                            .setTitle("Pilih Warna Huruf")
                            .setItems(colorNames) { _, cIdx ->
                                target.color = colorVals[cIdx]
                                Toast.makeText(this, "Warna huruf: ${colorNames[cIdx]}", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    4 -> {
                        val sizes = arrayOf("24sp (Kecil)", "36sp (Sedang)", "48sp (Standar Judul)", "64sp (Besar)", "80sp (Ekstra Besar)", "110sp (Maksimal Header)")
                        val sizeVals = floatArrayOf(24f, 36f, 48f, 64f, 80f, 110f)
                        AlertDialog.Builder(this)
                            .setTitle("Ukuran Huruf (Font Size)")
                            .setItems(sizes) { _, sIdx ->
                                target.fontSize = sizeVals[sIdx]
                                updatePreview()
                            }
                            .show()
                    }
                    5 -> {
                        val positions = arrayOf("Atas Layar", "Tengah Layar", "Bawah Layar", "Kiri Tengah", "Kanan Tengah")
                        AlertDialog.Builder(this)
                            .setTitle("Atur Posisi Preset Text")
                            .setItems(positions) { _, pIdx ->
                                when (pIdx) {
                                    0 -> { target.x = 0.5f; target.y = 0.2f }
                                    1 -> { target.x = 0.5f; target.y = 0.5f }
                                    2 -> { target.x = 0.5f; target.y = 0.8f }
                                    3 -> { target.x = 0.25f; target.y = 0.5f }
                                    4 -> { target.x = 0.75f; target.y = 0.5f }
                                }
                                Toast.makeText(this, "Posisi diatur: ${positions[pIdx]} (Geser jari di preview untuk posisi bebas)", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    6 -> {
                        Toast.makeText(this, "Sentuh dan geser jari Anda pada layar preview di atas untuk memindahkan teks secara bebas!", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun showStickerDialog() {
        activeDragTarget = DragTarget.STICKER
        val stickerCount = aeConfig.stickers.size
        val options = arrayOf(
            "➕ Tambah Stiker / Emoji Animasi Baru",
            "🎞️ Tipe Animasi Stiker (${aeConfig.stickers.lastOrNull()?.animationType ?: "PULSE"})",
            "📐 Skala Ukuran Stiker (${aeConfig.stickers.lastOrNull()?.scale ?: 1.0f}x)",
            "📍 Atur Posisi Stiker Preset",
            "🗑️ Hapus Stiker Terakhir ($stickerCount aktif)",
            "🧹 Bersihkan Semua Stiker",
            "💡 Info: Sentuh & Geser di layar preview untuk memindahkan stiker"
        )

        AlertDialog.Builder(this)
            .setTitle("🎉 Stiker & Animasi Efek Custom")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val emojiList = arrayOf(
                            "🔥 Api Membara", "⚡ Petir Kilat", "✨ Bintang Kilau", "🎬 Klaket Film",
                            "🚀 Roket Luar Angkasa", "❤️ Hati Cinta", "👑 Mahkota Juara", "💯 Skor Sempurna",
                            "💥 Ledakan Komik", "🌟 Bintang Emas", "🕶️ Kacamata Keren", "🏆 Piala Juara",
                            "💎 Berlian Mewah", "🍿 Popcorn Bioskop", "🎯 Target Akurat", "💣 Bom Dinamit",
                            "💫 Bintang Pusing", "🪐 Planet Saturnus", "🛸 UFO Alien", "🎮 Game Controller"
                        )
                        val emojiCodes = arrayOf(
                            "🔥", "⚡", "✨", "🎬",
                            "🚀", "❤️", "👑", "💯",
                            "💥", "🌟", "🕶️", "🏆",
                            "💎", "🍿", "🎯", "💣",
                            "💫", "🪐", "🛸", "🎮"
                        )
                        AlertDialog.Builder(this)
                            .setTitle("Pilih Stiker Animasi")
                            .setItems(emojiList) { _, eIdx ->
                                val newSticker = AeSticker(
                                    content = emojiCodes[eIdx],
                                    x = 0.5f,
                                    y = 0.4f,
                                    scale = 1.0f,
                                    animationType = "PULSE"
                                )
                                aeConfig.stickers.add(newSticker)
                                binding.btnVfxSticker.text = "🎉 Stiker (${aeConfig.stickers.size})"
                                Toast.makeText(this, "Stiker ${emojiCodes[eIdx]} ditambahkan! Sentuh & geser pada preview untuk memposisikan.", Toast.LENGTH_LONG).show()
                                updateBadge()
                                updatePreview()
                            }
                            .show()
                    }
                    1 -> {
                        val target = aeConfig.stickers.lastOrNull() ?: run {
                            Toast.makeText(this, "Tambahkan stiker terlebih dahulu", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        val animNames = arrayOf(
                            "Denyut (Pulse)",
                            "Melompat Pantul (Bounce)",
                            "Berputar 360° (Spin)",
                            "Goyang Dinamis (Wobble)",
                            "Mengapung Melayang (Float)",
                            "Diam Tanpa Animasi (None)"
                        )
                        val animCodes = arrayOf("PULSE", "BOUNCE", "SPIN", "WOBBLE", "FLOAT", "NONE")
                        AlertDialog.Builder(this)
                            .setTitle("Pilih Tipe Animasi Stiker")
                            .setItems(animNames) { _, aIdx ->
                                target.animationType = animCodes[aIdx]
                                Toast.makeText(this, "Animasi stiker: ${animNames[aIdx]}", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    2 -> {
                        val target = aeConfig.stickers.lastOrNull() ?: run {
                            Toast.makeText(this, "Tambahkan stiker terlebih dahulu", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        val scales = arrayOf("0.6x (Kecil)", "1.0x (Normal)", "1.5x (Besar)", "2.0x (Jumbo)", "2.5x (Maksimal)")
                        val scaleVals = floatArrayOf(0.6f, 1.0f, 1.5f, 2.0f, 2.5f)
                        AlertDialog.Builder(this)
                            .setTitle("Skala Ukuran Stiker")
                            .setItems(scales) { _, scIdx ->
                                target.scale = scaleVals[scIdx]
                                Toast.makeText(this, "Ukuran stiker: ${scaleVals[scIdx]}x", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    3 -> {
                        val target = aeConfig.stickers.lastOrNull() ?: run {
                            Toast.makeText(this, "Tambahkan stiker terlebih dahulu", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        val positions = arrayOf("Kiri Atas", "Kanan Atas", "Tengah Layar", "Kiri Bawah", "Kanan Bawah")
                        AlertDialog.Builder(this)
                            .setTitle("Atur Posisi Preset Stiker")
                            .setItems(positions) { _, pIdx ->
                                when (pIdx) {
                                    0 -> { target.x = 0.2f; target.y = 0.2f }
                                    1 -> { target.x = 0.8f; target.y = 0.2f }
                                    2 -> { target.x = 0.5f; target.y = 0.5f }
                                    3 -> { target.x = 0.2f; target.y = 0.8f }
                                    4 -> { target.x = 0.8f; target.y = 0.8f }
                                }
                                Toast.makeText(this, "Posisi stiker diatur: ${positions[pIdx]} (Bisa juga geser langsung di preview)", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    4 -> {
                        if (aeConfig.stickers.isNotEmpty()) {
                            val removed = aeConfig.stickers.removeAt(aeConfig.stickers.size - 1)
                            binding.btnVfxSticker.text = if (aeConfig.stickers.isNotEmpty()) "🎉 Stiker (${aeConfig.stickers.size})" else "🎉 Stiker Animasi"
                            Toast.makeText(this, "Stiker ${removed.content} dihapus", Toast.LENGTH_SHORT).show()
                            updateBadge()
                            updatePreview()
                        } else {
                            Toast.makeText(this, "Tidak ada stiker untuk dihapus", Toast.LENGTH_SHORT).show()
                        }
                    }
                    5 -> {
                        aeConfig.stickers.clear()
                        binding.btnVfxSticker.text = "🎉 Stiker Animasi"
                        Toast.makeText(this, "Semua stiker dibersihkan", Toast.LENGTH_SHORT).show()
                        updateBadge()
                        updatePreview()
                    }
                    6 -> {
                        Toast.makeText(this, "Sentuh dan geser jari Anda pada layar preview di atas untuk memindahkan stiker secara bebas!", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchRepos() {
        binding.ivVfxPreview.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    val vw = view.width.toFloat()
                    val vh = view.height.toFloat()
                    if (vw > 0 && vh > 0) {
                        val normX = (event.x / vw).coerceIn(0.05f, 0.95f)
                        val normY = (event.y / vh).coerceIn(0.05f, 0.95f)
                        if (activeDragTarget == DragTarget.STICKER && aeConfig.stickers.isNotEmpty()) {
                            val st = aeConfig.stickers.last()
                            st.x = normX
                            st.y = normY
                            updatePreview()
                        } else if (aeConfig.motionTexts.isNotEmpty()) {
                            val txt = aeConfig.motionTexts.last()
                            txt.x = normX
                            txt.y = normY
                            updatePreview()
                        } else if (aeConfig.stickers.isNotEmpty()) {
                            val st = aeConfig.stickers.last()
                            st.x = normX
                            st.y = normY
                            updatePreview()
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    view.performClick()
                    true
                }
                else -> false
            }
        }
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
                } else if (!isVideo) {
                    // Photo base: Render full After Effects composition to MP4 video using hardware MediaCodec
                    val width = if (resolution.width > 0) resolution.width else 1920
                    val height = if (resolution.height > 0) resolution.height else 1080
                    val fps = 30
                    val totalFrames = ((durationMs / 1000f) * fps).toInt().coerceIn(15, 300)
                    val stepMs = durationMs / totalFrames

                    val mimeType = android.media.MediaFormat.MIMETYPE_VIDEO_AVC
                    val codecFormat = android.media.MediaFormat.createVideoFormat(mimeType, width, height).apply {
                        setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(android.media.MediaFormat.KEY_BIT_RATE, resolution.bitrateMbps * 1_000_000)
                        setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }

                    val encoder = android.media.MediaCodec.createEncoderByType(mimeType)
                    encoder.configure(codecFormat, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val inputSurface = encoder.createInputSurface()
                    encoder.start()

                    val muxer = android.media.MediaMuxer(outputFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    var videoTrack = -1
                    val bufferInfo = android.media.MediaCodec.BufferInfo()
                    val destRect = android.graphics.Rect(0, 0, width, height)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

                    for (f in 0 until totalFrames) {
                        val t = f * stepMs
                        val rendered = AfterEffectsRenderer.renderFrame(base, t, aeConfig)
                        val canvas = inputSurface.lockCanvas(null)
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(rendered, null, destRect, paint)
                        inputSurface.unlockCanvasAndPost(canvas)

                        drainCodec(encoder, muxer, bufferInfo, false) { track -> videoTrack = track }
                        val pct = (f * 100 / totalFrames).coerceIn(0, 99)
                        withContext(Dispatchers.Main) {
                            binding.tvVfxProgressPercent.text = "Rendering VFX: $pct%"
                        }
                    }

                    encoder.signalEndOfInputStream()
                    drainCodec(encoder, muxer, bufferInfo, true) { track -> videoTrack = track }
                    encoder.stop()
                    encoder.release()
                    inputSurface.release()
                    if (videoTrack != -1) {
                        muxer.stop()
                    }
                    muxer.release()

                    withContext(Dispatchers.Main) {
                        binding.layoutVfxExportProgress.visibility = View.GONE
                        Toast.makeText(this@AfterEffectsStudioActivity, "✅ Video VFX After Effects ${resolution.label} selesai diexport!\nDisimpan di: ${outputFile.name}", Toast.LENGTH_LONG).show()
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

    private fun drainCodec(
        encoder: android.media.MediaCodec,
        muxer: android.media.MediaMuxer,
        bufferInfo: android.media.MediaCodec.BufferInfo,
        endOfStream: Boolean,
        onMuxerStart: (Int) -> Unit
    ) {
        val timeoutUs = 10_000L
        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (status == android.media.MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                val track = muxer.addTrack(newFormat)
                muxer.start()
                onMuxerStart(track)
            } else if (status >= 0) {
                val encodedData = encoder.getOutputBuffer(status)
                if (encodedData != null) {
                    if ((bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(0, encodedData, bufferInfo)
                    }
                }
                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
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
