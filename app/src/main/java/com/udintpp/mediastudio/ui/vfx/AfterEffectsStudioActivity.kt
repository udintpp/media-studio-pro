package com.udintpp.mediastudio.ui.vfx

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
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
import com.udintpp.mediastudio.util.StorageExportHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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

    // Live Video Decoding & Playback Engine
    private var mediaPlayer: MediaPlayer? = null
    private var videoSurface: Surface? = null
    private var isSurfaceAvailable = false
    private var rawVideoWidth = 1280
    private var rawVideoHeight = 720
    private var videoRotation = 0

    // Dedicated background render thread — keeps main thread free for UI input at 60fps
    private val renderThread = HandlerThread("AeRenderThread").also { it.start() }
    private val renderHandler = Handler(renderThread.looper)
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val isRenderPending = AtomicBoolean(false)

    // Double-buffering for silky-smooth, zero-GC 60fps rendering
    private var videoFrameBitmap: Bitmap? = null
    private var outputBitmapA: Bitmap? = null
    private var outputBitmapB: Bitmap? = null
    private var activeBufferIndex = 0
    private var lastPreviewUpdateMs: Long = 0L

    private val aeConfig = AeConfig()

    private enum class DragTarget {
        MOTION_TEXT, STICKER
    }
    private var activeDragTarget = DragTarget.MOTION_TEXT

    private enum class ActiveVfxSlider {
        GLOW, GLITCH, WIGGLE, LENS_FLARE, GRAIN, LIGHT_RAYS, TURBULENT, ZOOM_BLUR, SABER, ROT_X, ROT_Y, UNMULT, CONTOUR, CURL_NOISE, DOF, NONE
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

        setupTextureView()
        loadMedia(mediaUri!!)
        setupListeners()
    }

    private fun setupTextureView() {
        if (!isVideo) {
            binding.textureVideoSource.visibility = View.GONE
            return
        }
        binding.textureVideoSource.visibility = View.VISIBLE
        binding.textureVideoSource.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                isSurfaceAvailable = true
                videoSurface?.release()
                videoSurface = Surface(surface)
                mediaPlayer?.setSurface(videoSurface)
                if (isVideo && isPlaying) {
                    mediaPlayer?.start()
                }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                isSurfaceAvailable = false
                videoSurface?.release()
                videoSurface = null
                mediaPlayer?.setSurface(null)
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                onVideoFrameReady()
            }
        }
    }

    private fun initMediaPlayer(uri: Uri) {
        if (!isVideo) return
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AfterEffectsStudioActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                isLooping = true
                if (videoSurface != null) {
                    setSurface(videoSurface)
                }
                setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) {
                        rawVideoWidth = w
                        rawVideoHeight = h
                        adjustContainerAspect(w, h)
                    }
                }
                setOnPreparedListener { mp ->
                    if (durationMs <= 0L) {
                        durationMs = mp.duration.toLong().coerceAtLeast(1000L)
                        initTimeline()
                    }
                    if (videoSurface != null) {
                        mp.setSurface(videoSurface)
                    }
                    mp.seekTo(0)
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun adjustContainerAspect(videoWidth: Int, videoHeight: Int) {
        val container = binding.layoutVfxContainer
        container.post {
            val cw = container.width
            val ch = container.height
            if (cw <= 0 || ch <= 0 || videoWidth <= 0 || videoHeight <= 0) return@post

            val cAspect = cw.toFloat() / ch.toFloat()
            val vAspect = videoWidth.toFloat() / videoHeight.toFloat()

            val tw: Int
            val th: Int
            if (vAspect > cAspect) {
                tw = cw
                th = (cw / vAspect).toInt()
            } else {
                th = ch
                tw = (ch * vAspect).toInt()
            }

            val lp = binding.textureVideoSource.layoutParams as FrameLayout.LayoutParams
            lp.width = tw
            lp.height = th
            lp.gravity = Gravity.CENTER
            binding.textureVideoSource.layoutParams = lp

            val ivLp = binding.ivVfxPreview.layoutParams as FrameLayout.LayoutParams
            ivLp.width = tw
            ivLp.height = th
            ivLp.gravity = Gravity.CENTER
            binding.ivVfxPreview.layoutParams = ivLp
        }
    }

    private fun onVideoFrameReady() {
        val mp = mediaPlayer ?: return
        if (!isVideo) return

        val now = System.currentTimeMillis()
        // 16ms = ~60fps cap; skip only if we're already rendering a pending frame
        if (now - lastPreviewUpdateMs < 16L && isPlaying) return
        lastPreviewUpdateMs = now

        currentTimeMs = mp.currentPosition.toLong()
        binding.seekbarTimeline.progress = currentTimeMs.toInt()
        binding.tvTimelineTime.text = "${formatTime(currentTimeMs)} / ${formatTime(durationMs)}"

        val vw = binding.textureVideoSource.width
        val vh = binding.textureVideoSource.height
        if (vw <= 0 || vh <= 0) return

        // Downscale for smooth 60fps preview (960px wide max)
        val scale = if (vw > 960) 960f / vw else 1.0f
        val targetW = (vw * scale).toInt().coerceAtLeast(100)
        val targetH = (vh * scale).toInt().coerceAtLeast(100)

        if (videoFrameBitmap == null || videoFrameBitmap!!.width != targetW || videoFrameBitmap!!.height != targetH) {
            videoFrameBitmap?.recycle()
            videoFrameBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        }

        // Capture current video frame while still on main thread (TextureView requirement)
        binding.textureVideoSource.getBitmap(videoFrameBitmap!!)
        baseBitmap = videoFrameBitmap

        // Skip queuing new render if one is already in-flight (prevents render backpressure)
        if (!isRenderPending.compareAndSet(false, true)) return

        val frameCopy = videoFrameBitmap!!
        val capturedTimeMs = currentTimeMs
        val capturedConfig = aeConfig.copy()
        val bufIdx = activeBufferIndex
        val reuseBuf = if (bufIdx == 0) outputBitmapA else outputBitmapB

        renderHandler.post {
            val output = AfterEffectsRenderer.renderFrame(frameCopy, capturedTimeMs, capturedConfig, reuseBuf)
            isRenderPending.set(false)
            mainHandler.post {
                if (bufIdx == 0) outputBitmapA = output else outputBitmapB = output
                activeBufferIndex = 1 - bufIdx
                binding.ivVfxPreview.setImageBitmap(output)
            }
        }
    }

    private fun loadMedia(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isVideo) {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(this@AfterEffectsStudioActivity, uri)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val dur = durStr?.toLongOrNull() ?: 5000L
                    durationMs = dur.coerceIn(1000L, 300000L)
                    val rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    videoRotation = rotStr?.toIntOrNull() ?: 0
                    val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
                    val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
                    rawVideoWidth = if (videoRotation == 90 || videoRotation == 270) rawH else rawW
                    rawVideoHeight = if (videoRotation == 90 || videoRotation == 270) rawW else rawH

                    val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()

                    withContext(Dispatchers.Main) {
                        baseBitmap = frame ?: createFallbackBitmap()
                        initTimeline()
                        adjustContainerAspect(rawVideoWidth, rawVideoHeight)
                        initMediaPlayer(uri)
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
                        baseBitmap?.let { adjustContainerAspect(it.width, it.height) }
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
                    if (isVideo && mediaPlayer != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            mediaPlayer?.seekTo(currentTimeMs, MediaPlayer.SEEK_CLOSEST)
                        } else {
                            mediaPlayer?.seekTo(currentTimeMs.toInt())
                        }
                    } else {
                        updatePreview()
                    }
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

        // --- Official Adobe After Effects Tools & Extensions ---
        binding.btnQuickApply.setOnClickListener {
            showQuickApplyDialog()
        }

        binding.btnGridGuides.setOnClickListener {
            showGuidesDialog()
        }

        binding.btnSnapshot.setOnClickListener {
            takeSnapshot()
        }

        binding.btnVfxUnmult.setOnClickListener {
            showUnmultDialog()
        }

        binding.btnVfxContour.setOnClickListener {
            showContourDialog()
        }

        binding.btnVfxCurl.setOnClickListener {
            showCurlNoiseDialog()
        }

        binding.btnVfxDof.setOnClickListener {
            showDepthOfFieldDialog()
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
                    ActiveVfxSlider.UNMULT -> aeConfig.unmultThreshold = value / 100f
                    ActiveVfxSlider.CONTOUR -> aeConfig.contourThreshold = value / 100f
                    ActiveVfxSlider.CURL_NOISE -> aeConfig.curlNoiseSpeed = (value / 50f).coerceAtLeast(0.1f)
                    ActiveVfxSlider.DOF -> aeConfig.dofApertureBlur = (value / 100f) * 20f
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
            aeConfig.stickers.isNotEmpty(),
            aeConfig.isUnmultEnabled,
            aeConfig.isContourPaintEnabled,
            aeConfig.isCurlNoiseEnabled,
            aeConfig.isDepthOfFieldEnabled
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
                if (isVideo && mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val params = mediaPlayer?.playbackParams ?: PlaybackParams()
                        params.speed = aeConfig.speedMultiplier
                        mediaPlayer?.playbackParams = params
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
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
            "✨ Bling-Bling & Diamond Sparkles (Kilau Permata)",
            "🌟 Kunang-Kunang (Fireflies Bioluminescent Organik)",
            "⭐ Bintang & Kosmik (Twinkling Stars & Shooting Star)",
            "👑 Debu Emas Mewah (Royal Gold Flakes)",
            "🧚‍♀️ Magic Fairy Dust (Kilauan Mistik)",
            "🔥 Sparks & Embers (Percikan Api & Bara)",
            "🌧️ Rain & Mist (Hujan Sinematik)",
            "❄️ Snow Drift (Butiran Salju Lembut)",
            "🔮 Bokeh Orbs (Cahaya Aperture Optik)",
            "🎆 Kembang Api Tahun Baru (New Year Fireworks)",
            "🕌 Ketupat Lebaran (Eid Mubarak Festive)"
        )
        AlertDialog.Builder(this)
            .setTitle("Pilih Simulasi Partikel (Particle World)")
            .setItems(items) { _, which ->
                aeConfig.activeParticleType = when (which) {
                    1 -> AeEffectType.PARTICLE_BLING
                    2 -> AeEffectType.PARTICLE_FIREFLIES
                    3 -> AeEffectType.PARTICLE_STARS
                    4 -> AeEffectType.PARTICLE_GOLD_DUST
                    5 -> AeEffectType.PARTICLE_MAGIC_DUST
                    6 -> AeEffectType.PARTICLE_SPARKS
                    7 -> AeEffectType.PARTICLE_RAIN
                    8 -> AeEffectType.PARTICLE_SNOW
                    9 -> AeEffectType.PARTICLE_BOKEH
                    10 -> AeEffectType.PARTICLE_FIREWORKS
                    11 -> AeEffectType.PARTICLE_KETUPAT
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
            "📏 Ukuran Butiran Partikel (${aeConfig.particleSize}x)",
            "🎨 Palet Warna Partikel (${aeConfig.particleColorPalette})",
            "🧭 Arah & Pola Gerakan (${aeConfig.particleDirectionMode})",
            "💨 Turbulensi & Keacakan Arah (${aeConfig.particleTurbulence}x)",
            "⭐ Kilau Bintang 4-Sudut (Glint Flare): ${if (aeConfig.isParticleStarGlintEnabled) "ON" else "OFF"}",
            "💫 Laju Kedipan Partikel (Twinkle Rate: ${aeConfig.particleTwinkleRate}x)"
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
                        val counts = arrayOf("15 Partikel (Minimalis)", "35 Partikel (Ringan)", "60 Partikel (Normal)", "100 Partikel (Padat)", "160 Partikel (Intensif)", "250 Partikel (Badai Mewah)")
                        val countVals = intArrayOf(15, 35, 60, 100, 160, 250)
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
                        val sizes = arrayOf("0.5x (Kecil Halus)", "1.0x (Normal)", "1.5x (Besar)", "2.0x (Tebal Jelas)", "2.8x (Maksimal Orbs)")
                        val sizeVals = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f, 2.8f)
                        AlertDialog.Builder(this)
                            .setTitle("Ukuran Butiran Partikel")
                            .setItems(sizes) { _, szIdx ->
                                aeConfig.particleSize = sizeVals[szIdx]
                                Toast.makeText(this, "Ukuran partikel: ${sizeVals[szIdx]}x", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    4 -> {
                        val palettes = arrayOf(
                            "👑 Emas Mewah (Royal Gold & Amber)",
                            "💎 Berlian Cyan (Diamond Crystal)",
                            "🌟 Kunang-Kunang (Bioluminescent Lime-Yellow)",
                            "🌈 Pelangi Spektrum (Rainbow Holographic)",
                            "🤍 Kristal Putih (Pure Diamond White)",
                            "🔴 Ruby Merah (Ruby Crimson)",
                            "🟢 Zamrud Hijau (Emerald Glow)",
                            "🟣 Amethyst Ungu (Royal Violet)",
                            "🎨 Multi-Warna Spektakuler"
                        )
                        val paletteCodes = arrayOf("GOLD", "DIAMOND", "FIREFLY", "RAINBOW", "CRYSTAL_WHITE", "RUBY", "EMERALD", "AMETHYST", "MULTI")
                        AlertDialog.Builder(this)
                            .setTitle("Pilih Palet Warna Partikel")
                            .setItems(palettes) { _, pIdx ->
                                aeConfig.particleColorPalette = paletteCodes[pIdx]
                                Toast.makeText(this, "Palet warna: ${palettes[pIdx]}", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    5 -> {
                        val directions = arrayOf(
                            "🍃 Gerak Acak Alami (Random Wander / Brownian Motion - Tidak Baris)",
                            "💥 Memancar Bebas 360° (Radial Burst Outwards)",
                            "🎈 Melayang Naik Organik (Upward Float with Wind)",
                            "🌪️ Pusaran Kosmik (Vortex Swirl)",
                            "🍂 Melayang Turun Lembut (Downward Drift)"
                        )
                        val directionCodes = arrayOf("RANDOM_WANDER", "RADIAL_BURST", "UPWARD_FLOAT", "VORTEX_SWIRL", "DOWNWARD_DRIFT")
                        AlertDialog.Builder(this)
                            .setTitle("Pilih Arah & Pola Gerak Partikel")
                            .setItems(directions) { _, dIdx ->
                                aeConfig.particleDirectionMode = directionCodes[dIdx]
                                Toast.makeText(this, "Pola gerak: ${directions[dIdx]}", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    6 -> {
                        val turbulences = arrayOf("0.4x (Tenang & Lembut)", "1.0x (Alami Seimbang)", "1.8x (Dinamis Berombak)", "2.8x (Sangat Acak & Liar)")
                        val turbVals = floatArrayOf(0.4f, 1.0f, 1.8f, 2.8f)
                        AlertDialog.Builder(this)
                            .setTitle("Tingkat Turbulensi & Keacakan Gerak")
                            .setItems(turbulences) { _, tIdx ->
                                aeConfig.particleTurbulence = turbVals[tIdx]
                                Toast.makeText(this, "Turbulensi: ${turbVals[tIdx]}x", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    7 -> {
                        aeConfig.isParticleStarGlintEnabled = !aeConfig.isParticleStarGlintEnabled
                        Toast.makeText(this, if (aeConfig.isParticleStarGlintEnabled) "Kilau Bintang 4-Sudut: ON" else "Kilau Bintang: OFF", Toast.LENGTH_SHORT).show()
                        updatePreview()
                    }
                    8 -> {
                        val twinkles = arrayOf("0.5x (Kelip Halus)", "1.0x (Normal)", "2.0x (Kelip Cepat)", "3.0x (Bling Hiper Dinamis)")
                        val twinkleVals = floatArrayOf(0.5f, 1.0f, 2.0f, 3.0f)
                        AlertDialog.Builder(this)
                            .setTitle("Laju Kedipan / Pulsasi Partikel")
                            .setItems(twinkles) { _, twIdx ->
                                aeConfig.particleTwinkleRate = twinkleVals[twIdx]
                                Toast.makeText(this, "Laju kedipan: ${twinkleVals[twIdx]}x", Toast.LENGTH_SHORT).show()
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
        val shimmerStatus = if (currentText?.isShimmerEnabled == true) "ON (Aktif)" else "OFF"
        val starGlintStatus = if (currentText?.isStarGlintEnabled == true) "ON (Aktif)" else "OFF"

        val options = arrayOf(
            "🧊 Huruf Timbul 3D (Bevel & Extrusion): $embossStatus",
            "📐 Kedalaman Timbul 3D (${currentText?.embossDepth?.toInt() ?: 4}px)",
            "🔤 Pilihan Font Typography (${currentText?.fontFamily ?: "DEFAULT_BOLD"})",
            "🎨 Pilihan Warna Huruf (Lengkap dengan Emas 24K, Rose Gold, dll)",
            "✨ Kilauan Cahaya Sapuan Teks (Shimmer Metallic Sweep): $shimmerStatus",
            "🎨 Warna Kilauan Sapuan Teks (Emas, Perak, Cyan, Pelangi)",
            "⚡ Kecepatan Sapuan Kilau Teks (${currentText?.shimmerSpeed ?: 1.0f}x)",
            "⭐ Kilauan Bintang di Sudut Teks (Star Glint Sparkles): $starGlintStatus",
            "🎨 Warna Bintang Kilau di Teks",
            "📏 Ukuran Font Text (${currentText?.fontSize?.toInt() ?: 44}sp)",
            "📍 Atur Posisi Text Preset",
            "💡 Info: Sentuh & Geser jari di layar preview untuk posisi bebas"
        )

        AlertDialog.Builder(this)
            .setTitle("✨ Kustomisasi Teks, Huruf Timbul & Kilauan")
            .setItems(options) { _, which ->
                val target = aeConfig.motionTexts.lastOrNull() ?: run {
                    val newText = AeMotionText(text = "AFTER EFFECTS PRO", color = Color.parseColor("#FFD700"))
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
                        val colorNames = arrayOf(
                            "👑 Emas Murni 24K (Pure Gold #FFD700)",
                            "🌹 Rose Gold Mewah (#B76E79)",
                            "🥂 Champagne Gold Sinematik (#F7E7CE)",
                            "🥉 Bronze Metalik (#CD7F32)",
                            "✨ Emas Kuning Cerah (#FBBF24)",
                            "🤍 Putih Murni (#FFFFFF)",
                            "💎 Cyan Berlian Neon (#06B6D4)",
                            "💖 Pink Magenta Glamor (#EC4899)",
                            "💚 Hijau Limau Neon (#84CC16)",
                            "🔴 Merah Crimson (#EF4444)",
                            "💜 Ungu Amethyst (#A855F7)",
                            "🖤 Hitam Onyx (#0F172A)"
                        )
                        val colorVals = intArrayOf(
                            Color.parseColor("#FFD700"),
                            Color.parseColor("#B76E79"),
                            Color.parseColor("#F7E7CE"),
                            Color.parseColor("#CD7F32"),
                            Color.parseColor("#FBBF24"),
                            Color.WHITE,
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
                        target.isShimmerEnabled = !target.isShimmerEnabled
                        Toast.makeText(this, if (target.isShimmerEnabled) "Kilauan Sapuan Teks: ON" else "Kilauan Sapuan: OFF", Toast.LENGTH_SHORT).show()
                        updatePreview()
                    }
                    5 -> {
                        val shimmerNames = arrayOf("Emas Murni (#FFD700)", "Perak Berlian (#E2E8F0)", "Cyan Neon (#06B6D4)", "Pink Rose (#EC4899)", "Amber Warm (#F59E0B)")
                        val shimmerColors = intArrayOf(
                            Color.parseColor("#FFD700"), Color.parseColor("#E2E8F0"), Color.parseColor("#06B6D4"),
                            Color.parseColor("#EC4899"), Color.parseColor("#F59E0B")
                        )
                        AlertDialog.Builder(this)
                            .setTitle("Pilih Warna Kilauan Sapuan Teks")
                            .setItems(shimmerNames) { _, scIdx ->
                                target.shimmerColor = shimmerColors[scIdx]
                                target.isShimmerEnabled = true
                                Toast.makeText(this, "Warna kilauan: ${shimmerNames[scIdx]}", Toast.LENGTH_SHORT).show()
                                updatePreview()
                            }
                            .show()
                    }
                    6 -> {
                        val speeds = arrayOf("0.5x (Lambat Berwibawa)", "1.0x (Normal Sinematik)", "1.8x (Cepat Berkilau)", "2.5x (Kilat Mewah)")
                        val speedVals = floatArrayOf(0.5f, 1.0f, 1.8f, 2.5f)
                        AlertDialog.Builder(this)
                            .setTitle("Kecepatan Sapuan Kilau Teks")
                            .setItems(speeds) { _, spIdx ->
                                target.shimmerSpeed = speedVals[spIdx]
                                target.isShimmerEnabled = true
                                updatePreview()
                            }
                            .show()
                    }
                    7 -> {
                        target.isStarGlintEnabled = !target.isStarGlintEnabled
                        Toast.makeText(this, if (target.isStarGlintEnabled) "Kilau Bintang Teks: ON" else "Kilau Bintang Teks: OFF", Toast.LENGTH_SHORT).show()
                        updatePreview()
                    }
                    8 -> {
                        val starColors = arrayOf("Emas Murni (#FFD700)", "Putih Berlian (#FFFFFF)", "Cyan Elektrik (#06B6D4)", "Rose Pink (#F43F5E)")
                        val starVals = intArrayOf(Color.parseColor("#FFD700"), Color.WHITE, Color.parseColor("#06B6D4"), Color.parseColor("#F43F5E"))
                        AlertDialog.Builder(this)
                            .setTitle("Pilih Warna Bintang Kilau")
                            .setItems(starColors) { _, stIdx ->
                                target.starGlintColor = starVals[stIdx]
                                target.isStarGlintEnabled = true
                                updatePreview()
                            }
                            .show()
                    }
                    9 -> {
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
                    10 -> {
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
                    11 -> {
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
        val base = (videoFrameBitmap ?: baseBitmap)?.let {
            // Safe copy reference — render will use this immutable bitmap
            it
        } ?: return

        val capturedTimeMs = currentTimeMs
        val capturedConfig = aeConfig.copy()
        val bufIdx = activeBufferIndex
        val reuseBuf = if (bufIdx == 0) outputBitmapA else outputBitmapB

        // Render on dedicated thread → post result back to main thread
        renderHandler.post {
            val output = AfterEffectsRenderer.renderFrame(base, capturedTimeMs, capturedConfig, reuseBuf)
            mainHandler.post {
                if (bufIdx == 0) outputBitmapA = output else outputBitmapB = output
                activeBufferIndex = 1 - bufIdx
                binding.ivVfxPreview.setImageBitmap(output)
            }
        }
    }

    private fun startPlayback() {
        isPlaying = true
        binding.btnPlayPause.setImageResource(com.udintpp.mediastudio.R.drawable.ic_pause)

        if (isVideo) {
            try {
                if (mediaPlayer == null && mediaUri != null) {
                    initMediaPlayer(mediaUri!!)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val params = mediaPlayer?.playbackParams ?: PlaybackParams()
                    params.speed = aeConfig.speedMultiplier
                    mediaPlayer?.playbackParams = params
                }
                mediaPlayer?.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            timelineAnimator?.cancel()
            timelineAnimator = ValueAnimator.ofInt(currentTimeMs.toInt(), durationMs.toInt()).apply {
                duration = ((durationMs - currentTimeMs) / aeConfig.effectSpeed).toLong().coerceAtLeast(100L)
                interpolator = LinearInterpolator()
                // Lock animator to 60fps update rate — prevents flooding renderHandler faster than GPU can consume
                setEvaluator(android.animation.IntEvaluator())
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Int
                    currentTimeMs = p.toLong()
                    binding.seekbarTimeline.progress = p
                    binding.tvTimelineTime.text = "${formatTime(currentTimeMs)} / ${formatTime(durationMs)}"
                    // Only queue new render if previous one has completed (backpressure guard)
                    if (!isRenderPending.get()) {
                        updatePreview()
                    }
                }
                start()
            }
        }
    }

    private fun pausePlayback() {
        isPlaying = false
        binding.btnPlayPause.setImageResource(com.udintpp.mediastudio.R.drawable.ic_play)
        if (isVideo) {
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            timelineAnimator?.cancel()
            timelineAnimator = null
        }
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

        val outputFile = StorageExportHelper.createVideoOutputFile(
            context = this@AfterEffectsStudioActivity,
            prefix = "MediaStudio_AfterEffects_${resolution.name}",
            extension = format.extension
        )

        binding.layoutVfxExportProgress.visibility = View.VISIBLE
        binding.tvVfxProgressPercent.text = "Mengekspor VFX ${resolution.label} (${format.displayName})..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // If format is GIF or image slideshow, render frame sequence
                if (format == VideoExportFormat.GIF) {
                    val totalFrames = 30
                    val frameList = mutableListOf<Bitmap>()
                    val stepMs = durationMs / totalFrames

                    val exportCfg = aeConfig.copy(isGuideGridEnabled = false)
                    for (i in 0 until totalFrames) {
                        val t = i * stepMs
                        val rendered = AfterEffectsRenderer.renderFrame(base, t, exportCfg)
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

                    StorageExportHelper.syncExportedFile(this@AfterEffectsStudioActivity, outputFile, format.mimeType)
                    val successMsg = StorageExportHelper.getSuccessNotificationMessage(outputFile.name)

                    withContext(Dispatchers.Main) {
                        binding.layoutVfxExportProgress.visibility = View.GONE
                        Toast.makeText(this@AfterEffectsStudioActivity, successMsg, Toast.LENGTH_LONG).show()
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

                    val exportCfg = aeConfig.copy(isGuideGridEnabled = false)
                    for (f in 0 until totalFrames) {
                        val t = f * stepMs
                        val rendered = AfterEffectsRenderer.renderFrame(base, t, exportCfg)
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

                    StorageExportHelper.syncExportedFile(this@AfterEffectsStudioActivity, outputFile, format.mimeType)
                    val successMsg = StorageExportHelper.getSuccessNotificationMessage(outputFile.name)

                    withContext(Dispatchers.Main) {
                        binding.layoutVfxExportProgress.visibility = View.GONE
                        Toast.makeText(this@AfterEffectsStudioActivity, successMsg, Toast.LENGTH_LONG).show()
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
                                    StorageExportHelper.syncExportedFile(this@AfterEffectsStudioActivity, outputFile, format.mimeType)
                                    val successMsg = StorageExportHelper.getSuccessNotificationMessage(outputFile.name)
                                    binding.layoutVfxExportProgress.visibility = View.GONE
                                    Toast.makeText(this@AfterEffectsStudioActivity, successMsg, Toast.LENGTH_LONG).show()
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

    // --- Official Adobe After Effects Extended Features ---

    private fun showQuickApplyDialog() {
        val tools = arrayOf(
            "🌟 Optical Glow",
            "⚡ RGB Glitch & Split",
            "📳 Camera Wiggle (Shake)",
            "☀️ CC Light Rays (God Rays)",
            "🌊 Turbulent Displace (Liquify)",
            "🌀 CC Radial Zoom Blur",
            "⚡ Saber Neon Edge Effect",
            "☀️ Anamorphic Lens Flare",
            "🎨 Lumetri Color Grading",
            "✨ Partikel Bling, Kunang-Kunang, Bintang, Emas & Sparks",
            "🎛️ Parameter Partikel (Warna, Arah Acak & Turbulensi)",
            "🎞️ Film Grain 35mm Real Stock",
            "🔤 Motion Text Animasi",
            "✨ Huruf Timbul 3D, Kilauan Shimmer & Bintang Teks",
            "🎉 Stiker Animasi Giphy/PNG",
            "🎵 Audio Waveform / Spectrum React",
            "🎭 Blending Modes (Add, Screen, dll)",
            "⬛ Unmult Alpha Keyer (Hapus Latar Hitam/Putih)",
            "🎨 Contour Paint (Cel-Shading Outlines)",
            "🌪️ Curl Noise (Organic Fluid Smoke Swirl)",
            "📷 3D Camera Depth of Field",
            "📐 Composition Guides & Safe Margins",
            "📸 Snapshot Frame ke Clipboard",
            "⚙️ Pengaturan Komposisi (Resolusi, Rasio, Durasi)"
        )

        AlertDialog.Builder(this)
            .setTitle("🔍 Quick Apply - Adobe After Effects")
            .setItems(tools) { _, which ->
                when (which) {
                    0 -> binding.btnVfxGlow.performClick()
                    1 -> binding.btnVfxGlitch.performClick()
                    2 -> binding.btnVfxWiggle.performClick()
                    3 -> binding.btnVfxLightRays.performClick()
                    4 -> binding.btnVfxTurbulent.performClick()
                    5 -> binding.btnVfxZoomBlur.performClick()
                    6 -> binding.btnVfxSaber.performClick()
                    7 -> binding.btnVfxLensFlare.performClick()
                    8 -> showLumetriDialog()
                    9 -> showParticleSelectionDialog()
                    10 -> showParticleSettingsDialog()
                    11 -> binding.btnVfxFilmGrain.performClick()
                    12 -> showMotionTextDialog()
                    13 -> showTextStyleDialog()
                    14 -> showStickerDialog()
                    15 -> binding.btnVfxAudioSpectrum.performClick()
                    16 -> showBlendModeDialog()
                    17 -> showUnmultDialog()
                    18 -> showContourDialog()
                    19 -> showCurlNoiseDialog()
                    20 -> showDepthOfFieldDialog()
                    21 -> showGuidesDialog()
                    22 -> takeSnapshot()
                    23 -> showCompSettingsDialog()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showGuidesDialog() {
        val options = arrayOf(
            "Matikan Guides",
            "📐 Rule of Thirds (Garis & Titik Emas)",
            "🛡️ Title & Action Safe Margins (Broadcast)",
            "📏 Grid 4x4 Simetris"
        )
        val types = arrayOf("NONE", "RULE_OF_THIRDS", "SAFE_MARGIN", "GRID_4X4")

        AlertDialog.Builder(this)
            .setTitle("📐 Composition Guides & Grid Overlay")
            .setItems(options) { _, which ->
                if (which == 0) {
                    aeConfig.isGuideGridEnabled = false
                    Toast.makeText(this, "Guides dimatikan", Toast.LENGTH_SHORT).show()
                } else {
                    aeConfig.isGuideGridEnabled = true
                    aeConfig.guideGridType = types[which]
                    Toast.makeText(this, "Guides: ${options[which]} aktif", Toast.LENGTH_SHORT).show()
                }
                updatePreview()
            }
            .show()
    }

    private fun takeSnapshot() {
        try {
            val currentFrame = if (isVideo && videoFrameBitmap != null) {
                videoFrameBitmap!!
            } else {
                baseBitmap ?: createFallbackBitmap()
            }
            val snapshotBmp = Bitmap.createBitmap(currentFrame.width, currentFrame.height, Bitmap.Config.ARGB_8888)
            val cfgNoGuide = aeConfig.copy(isGuideGridEnabled = false)
            AfterEffectsRenderer.renderFrame(currentFrame, currentTimeMs, cfgNoGuide, snapshotBmp)

            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MediaStudioPro_Snapshots")
            if (!dir.exists()) dir.mkdirs()
            val fileName = "AE_Snapshot_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.png"
            val file = File(dir, fileName)
            val out = FileOutputStream(file)
            snapshotBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.close()

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AE Snapshot Path", file.absolutePath)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(this, "📸 Snapshot disimpan di:\n${file.name}\n(Path disalin ke clipboard!)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengambil snapshot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUnmultDialog() {
        val options = arrayOf(
            if (aeConfig.isUnmultEnabled) "🔴 Nonaktifkan Unmult" else "🟢 Aktifkan Unmult",
            "Mode: Hapus Background Hitam (Black to Alpha)",
            "Mode: Hapus Background Putih (White to Alpha)",
            "🎛️ Atur Threshold / Sensitivitas (${(aeConfig.unmultThreshold * 100).toInt()}%)"
        )
        AlertDialog.Builder(this)
            .setTitle("⬛ Knoll Unmult Alpha Keyer")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        aeConfig.isUnmultEnabled = !aeConfig.isUnmultEnabled
                        binding.btnVfxUnmult.text = if (aeConfig.isUnmultEnabled) "⬛ Unmult: ON" else "⬛ Unmult Alpha"
                        updateBadge()
                        updatePreview()
                    }
                    1 -> {
                        aeConfig.isUnmultEnabled = true
                        aeConfig.unmultMode = "BLACK"
                        binding.btnVfxUnmult.text = "⬛ Unmult: ON"
                        updateBadge()
                        updatePreview()
                        Toast.makeText(this, "Unmult: Menghapus latar hitam", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        aeConfig.isUnmultEnabled = true
                        aeConfig.unmultMode = "WHITE"
                        binding.btnVfxUnmult.text = "⬛ Unmult: ON"
                        updateBadge()
                        updatePreview()
                        Toast.makeText(this, "Unmult: Menghapus latar putih", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        activeSlider = ActiveVfxSlider.UNMULT
                        updateSliderUI("Sensitivitas Unmult Alpha", (aeConfig.unmultThreshold * 100).toInt())
                    }
                }
            }
            .show()
    }

    private fun showContourDialog() {
        val options = arrayOf(
            if (aeConfig.isContourPaintEnabled) "🔴 Nonaktifkan Contour Paint" else "🟢 Aktifkan Contour Paint",
            "Garis Tipis (1.5px)",
            "Garis Sedang (3.0px)",
            "Garis Tebal (5.0px)",
            "🎛️ Atur Sensitivitas Outline Contour"
        )
        AlertDialog.Builder(this)
            .setTitle("🎨 Contour Paint (Cel-Shading Outlines)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        aeConfig.isContourPaintEnabled = !aeConfig.isContourPaintEnabled
                        binding.btnVfxContour.text = if (aeConfig.isContourPaintEnabled) "🎨 Contour: ON" else "🎨 Contour Paint"
                        updateBadge()
                        updatePreview()
                    }
                    1 -> {
                        aeConfig.isContourPaintEnabled = true
                        aeConfig.contourLineWidth = 1.5f
                        binding.btnVfxContour.text = "🎨 Contour: ON"
                        updateBadge()
                        updatePreview()
                    }
                    2 -> {
                        aeConfig.isContourPaintEnabled = true
                        aeConfig.contourLineWidth = 3.0f
                        binding.btnVfxContour.text = "🎨 Contour: ON"
                        updateBadge()
                        updatePreview()
                    }
                    3 -> {
                        aeConfig.isContourPaintEnabled = true
                        aeConfig.contourLineWidth = 5.0f
                        binding.btnVfxContour.text = "🎨 Contour: ON"
                        updateBadge()
                        updatePreview()
                    }
                    4 -> {
                        activeSlider = ActiveVfxSlider.CONTOUR
                        updateSliderUI("Sensitivitas Contour Paint", (aeConfig.contourThreshold * 100).toInt())
                    }
                }
            }
            .show()
    }

    private fun showCurlNoiseDialog() {
        val options = arrayOf(
            if (aeConfig.isCurlNoiseEnabled) "🔴 Nonaktifkan Curl Noise" else "🟢 Aktifkan Curl Noise",
            "Kecepatan: Aliran Santai (Slow Drift)",
            "Kecepatan: Pusaran Normal (Natural Flow)",
            "Kecepatan: Tornado Turbulen (Rapid Vortex)",
            "🎛️ Atur Kecepatan Fluid Custom"
        )
        AlertDialog.Builder(this)
            .setTitle("🌪️ Curl Noise (Organic Fluid Swirl)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        aeConfig.isCurlNoiseEnabled = !aeConfig.isCurlNoiseEnabled
                        binding.btnVfxCurl.text = if (aeConfig.isCurlNoiseEnabled) "🌪️ Curl: ON" else "🌪️ Curl Noise"
                        updateBadge()
                        updatePreview()
                    }
                    1 -> {
                        aeConfig.isCurlNoiseEnabled = true
                        aeConfig.curlNoiseSpeed = 0.5f
                        binding.btnVfxCurl.text = "🌪️ Curl: ON"
                        updateBadge()
                        updatePreview()
                    }
                    2 -> {
                        aeConfig.isCurlNoiseEnabled = true
                        aeConfig.curlNoiseSpeed = 1.0f
                        binding.btnVfxCurl.text = "🌪️ Curl: ON"
                        updateBadge()
                        updatePreview()
                    }
                    3 -> {
                        aeConfig.isCurlNoiseEnabled = true
                        aeConfig.curlNoiseSpeed = 2.0f
                        binding.btnVfxCurl.text = "🌪️ Curl: ON"
                        updateBadge()
                        updatePreview()
                    }
                    4 -> {
                        activeSlider = ActiveVfxSlider.CURL_NOISE
                        updateSliderUI("Kecepatan Pusaran Fluid", (aeConfig.curlNoiseSpeed * 50).toInt())
                    }
                }
            }
            .show()
    }

    private fun showDepthOfFieldDialog() {
        val options = arrayOf(
            if (aeConfig.isDepthOfFieldEnabled) "🔴 Nonaktifkan Depth of Field" else "🟢 Aktifkan Depth of Field",
            "Fokus: Area Depan (Foreground)",
            "Fokus: Area Tengah (Midground)",
            "Fokus: Area Belakang (Background)",
            "🎛️ Atur Aperture / Bokeh Blur"
        )
        AlertDialog.Builder(this)
            .setTitle("📷 3D Camera Depth of Field")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        aeConfig.isDepthOfFieldEnabled = !aeConfig.isDepthOfFieldEnabled
                        binding.btnVfxDof.text = if (aeConfig.isDepthOfFieldEnabled) "📷 DOF: ON" else "📷 3D Depth Field"
                        updateBadge()
                        updatePreview()
                    }
                    1 -> {
                        aeConfig.isDepthOfFieldEnabled = true
                        aeConfig.dofFocusPoint = 0.25f
                        binding.btnVfxDof.text = "📷 DOF: ON"
                        updateBadge()
                        updatePreview()
                    }
                    2 -> {
                        aeConfig.isDepthOfFieldEnabled = true
                        aeConfig.dofFocusPoint = 0.50f
                        binding.btnVfxDof.text = "📷 DOF: ON"
                        updateBadge()
                        updatePreview()
                    }
                    3 -> {
                        aeConfig.isDepthOfFieldEnabled = true
                        aeConfig.dofFocusPoint = 0.75f
                        binding.btnVfxDof.text = "📷 DOF: ON"
                        updateBadge()
                        updatePreview()
                    }
                    4 -> {
                        activeSlider = ActiveVfxSlider.DOF
                        updateSliderUI("Aperture Bokeh Blur", (aeConfig.dofApertureBlur * 5).toInt())
                    }
                }
            }
            .show()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    override fun onPause() {
        super.onPause()
        pausePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        pausePlayback()
        // Stop background render thread first to prevent in-flight renders accessing recycled bitmaps
        renderHandler.removeCallbacksAndMessages(null)
        renderThread.quitSafely()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        videoSurface?.release()
        videoSurface = null
        videoFrameBitmap?.recycle()
        videoFrameBitmap = null
        outputBitmapA?.recycle()
        outputBitmapA = null
        outputBitmapB?.recycle()
        outputBitmapB = null
    }
}
