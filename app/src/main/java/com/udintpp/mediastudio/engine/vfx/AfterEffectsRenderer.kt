package com.udintpp.mediastudio.engine.vfx

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.udintpp.mediastudio.data.model.AeConfig
import com.udintpp.mediastudio.data.model.AeEffectType
import com.udintpp.mediastudio.data.model.AeKeyframe
import com.udintpp.mediastudio.data.model.AeMask
import com.udintpp.mediastudio.data.model.AeMaskShape
import com.udintpp.mediastudio.data.model.AeMediaOverlay
import com.udintpp.mediastudio.data.model.AeMotionText
import com.udintpp.mediastudio.data.model.AeOverlayTransition
import com.udintpp.mediastudio.data.model.AeSticker
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object AfterEffectsRenderer {

    private val random = Random()

    /** Cubic Hermite smoothstep for ultra-smooth interpolation (no abrupt edges) */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Renders all active Adobe After Effects visual effects, keyframe transformations,
     * 3D perspective layers, shape masking, particle simulations, optical glow,
     * and motion graphics onto a frame bitmap.
     */
    fun renderFrame(
        src: Bitmap,
        timeMs: Long,
        config: AeConfig,
        reusableOutput: Bitmap? = null
    ): Bitmap {
        val w = if (config.compWidth > 0) config.compWidth else src.width
        val h = if (config.compHeight > 0) config.compHeight else src.height

        val output = if (reusableOutput != null && !reusableOutput.isRecycled && reusableOutput.width == w && reusableOutput.height == h) {
            reusableOutput.eraseColor(Color.TRANSPARENT)
            reusableOutput
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 0. Render Background Layer (BG Custom: Solid Color, Gradient, Blur Media, Custom Image, Transparent)
        when (config.bgMode) {
            "SOLID_COLOR" -> {
                canvas.drawColor(config.bgColor)
            }
            "GRADIENT" -> {
                renderBackgroundGradient(canvas, w, h, config.bgGradientPreset)
            }
            "BLUR_MEDIA" -> {
                renderBlurredMediaBackground(canvas, src, w, h, config.bgBlurRadius)
            }
            "CUSTOM_IMAGE" -> {
                val bgBmp = config.bgCustomBitmap
                if (bgBmp != null && !bgBmp.isRecycled) {
                    val srcR = Rect(0, 0, bgBmp.width, bgBmp.height)
                    val dstR = Rect(0, 0, w, h)
                    canvas.drawBitmap(bgBmp, srcR, dstR, paint)
                } else {
                    canvas.drawColor(config.bgColor)
                }
            }
            "TRANSPARENT" -> {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
            else -> {
                canvas.drawColor(config.bgColor)
            }
        }

        // 1. Evaluate Keyframing (Position, Scale, Rotation, 3D RotX/Y, Opacity)
        val transform = evaluateKeyframeTransform(config.keyframes, timeMs)

        // 2. Evaluate Camera Wiggle Shake (AE Handheld Wiggle)
        val wiggleOffset = if (config.isWiggleEnabled) {
            evaluateCameraWiggle(timeMs, config.wiggleFrequency, config.wiggleAmplitude)
        } else {
            PointF(0f, 0f)
        }

        // Apply transforms to canvas (including 3D Layer Space, Offset & Flip)
        canvas.save()
        val centerX = w / 2f
        val centerY = h / 2f
        canvas.translate(
            centerX + transform.posX + wiggleOffset.x + config.baseMediaOffsetX,
            centerY + transform.posY + wiggleOffset.y + config.baseMediaOffsetY
        )
        canvas.rotate(transform.rotation)

        // FLIP (Mirror Horizontal / Vertical)
        val flipX = if (config.isFlipHorizontal) -1f else 1f
        val flipY = if (config.isFlipVertical) -1f else 1f
        canvas.scale(transform.scale * flipX, transform.scale * flipY)

        // 3D Perspective Rotation (After Effects 3D Switch)
        if (config.is3DLayerEnabled || transform.rotX != 0f || transform.rotY != 0f) {
            val camera = Camera()
            camera.save()
            camera.translate(0f, 0f, config.cameraDistanceZ)
            camera.rotateX(transform.rotX + if (config.is3DLayerEnabled) config.rotateX else 0f)
            camera.rotateY(transform.rotY + if (config.is3DLayerEnabled) config.rotateY else 0f)
            val matrix3D = Matrix()
            camera.getMatrix(matrix3D)
            camera.restore()
            canvas.concat(matrix3D)
        }

        canvas.translate(-centerX, -centerY)

        // Masking & Rotoscoping Clip
        if (config.mask.isEnabled) {
            applyMaskClip(canvas, w, h, config.mask)
        }

        paint.alpha = (transform.opacity * 255).toInt().coerceIn(0, 255)
        paint.xfermode = PorterDuffXfermode(config.blendMode.porterDuffMode)

        // Base Media Source with CROP (if enabled)
        val processedSrc: Bitmap = if (config.isCropEnabled) {
            val cl = (config.cropLeftPercent * src.width).toInt().coerceIn(0, src.width - 2)
            val ct = (config.cropTopPercent * src.height).toInt().coerceIn(0, src.height - 2)
            val cr = (config.cropRightPercent * src.width).toInt().coerceIn(cl + 2, src.width)
            val cb = (config.cropBottomPercent * src.height).toInt().coerceIn(ct + 2, src.height)
            val cropW = max(2, cr - cl)
            val cropH = max(2, cb - ct)
            if (cropW < src.width || cropH < src.height) {
                Bitmap.createBitmap(src, cl, ct, cropW, cropH)
            } else {
                src
            }
        } else {
            src
        }

        // Calculate DstRect for base media with RESIZE & FIT MODE
        val baseDstRect = calculateBaseMediaDstRect(
            processedSrc.width, processedSrc.height,
            w, h,
            config.baseMediaFitMode,
            config.baseMediaScale
        )

        canvas.save()
        canvas.translate(baseDstRect.left, baseDstRect.top)
        val scaleW = baseDstRect.width() / processedSrc.width.toFloat()
        val scaleH = baseDstRect.height() / processedSrc.height.toFloat()
        canvas.scale(scaleW, scaleH)

        // 3. Render Base Video Frame, Flag Wave, Turbulent Displace, or RGB Split Glitch
        if (config.isFlagWaveEnabled) {
            renderFlagClothWave(canvas, processedSrc, processedSrc.width, processedSrc.height, timeMs, config, paint)
        } else if (config.isRgbGlitchEnabled) {
            renderRgbGlitch(canvas, processedSrc, timeMs, config.glitchIntensity, config.glitchScanlines, paint)
        } else if (config.isTurbulentDisplaceEnabled) {
            renderTurbulentDisplace(canvas, processedSrc, timeMs, config.turbulentAmount, config.turbulentSpeed, paint)
        } else {
            canvas.drawBitmap(processedSrc, 0f, 0f, paint)
        }
        canvas.restore()

        if (processedSrc != src) {
            processedSrc.recycle()
        }

        canvas.restore()

        // 4. Optical Deep Glow
        if (config.isOpticalGlowEnabled) {
            renderOpticalGlow(canvas, src, config.glowIntensity, config.glowRadius, config.glowColor)
        }

        // 5. Anamorphic Lens Flare
        if (config.isLensFlareEnabled) {
            renderAnamorphicLensFlare(canvas, w, h, config.flareX, config.flareY, config.flareIntensity, timeMs)
        }

        // 6. CC Light Rays (God Rays)
        if (config.isLightRaysEnabled) {
            renderCcLightRays(canvas, w, h, config.lightRaysX, config.lightRaysY, config.lightRaysIntensity, config.lightRaysLength, timeMs)
        }

        // 7. CC Radial Fast Blur / Zoom Blur
        if (config.isZoomBlurEnabled) {
            renderZoomBlur(canvas, src, config.zoomBlurAmount)
        }

        // 8. Saber Laser & Neon Edge
        if (config.isSaberEdgeEnabled) {
            renderSaberEdgeGlow(canvas, src, config.saberGlowColor, config.saberWidth, timeMs)
        }

        // 9. Particle World Simulation
        if (config.activeParticleType != null) {
            renderParticleWorld(canvas, w, h, timeMs, config)
        }

        // 10. 35mm Celluloid Film Grain
        if (config.isFilmGrainEnabled) {
            renderFilmGrain(canvas, w, h, timeMs, config.grainAmount)
        }

        // 11. Lumetri Color Curves Grading
        if (config.lumetriPreset != "NONE") {
            renderLumetriColor(canvas, w, h, config.lumetriPreset)
        }

        // 12. Audio Waveform Spectrum Visualizer
        if (config.isAudioSpectrumEnabled) {
            renderAudioWaveform(canvas, w, h, timeMs)
        }

        // 13. Motion Graphics Text
        for (motionText in config.motionTexts) {
            renderMotionText(canvas, w, h, timeMs, motionText, config.effectSpeed)
        }

        // 14. Animated Stickers & Emoji Overlays
        for (sticker in config.stickers) {
            renderSticker(canvas, w, h, timeMs, sticker, config.effectSpeed)
        }

        // 14b. Menumpuk Video atau Foto (Media Overlay Layers with Scale, Intensity, Duration & Transitions)
        for (overlay in config.mediaOverlays) {
            if (timeMs in overlay.startTimeMs..overlay.endTimeMs) {
                renderMediaOverlay(canvas, w, h, timeMs, overlay)
            }
        }

        // 15. Unmult Alpha Keyer (Black/White Background Removal)
        if (config.isUnmultEnabled) {
            applyUnmult(canvas, output, w, h, config.unmultMode, config.unmultThreshold)
        }

        // 16. Contour Paint (Cel-Shaded Outline Art)
        if (config.isContourPaintEnabled) {
            applyContourPaint(canvas, w, h, src, config.contourThreshold, config.contourLineWidth)
        }

        // 17. Curl Noise (Organic Fluid Swirl)
        if (config.isCurlNoiseEnabled) {
            renderCurlNoise(canvas, w, h, timeMs, config.curlNoiseSpeed * config.effectSpeed)
        }

        // 18. 3D Camera Depth of Field
        if (config.isDepthOfFieldEnabled) {
            applyDepthOfField(canvas, w, h, config.dofFocusPoint, config.dofApertureBlur)
        }

        // 19. Composition Guides & Grid Overlay
        if (config.isGuideGridEnabled) {
            drawCompositionGuides(canvas, w, h, config.guideGridType)
        }

        // 20. Master Cinematic Finishing Pass (Pro-Mist Bloom, Natural Film Texture, Vignette & Letterbox)
        if (config.isCinematicProMistEnabled) {
            renderCinematicProMist(canvas, output, w, h, config.proMistIntensity, config.proMistWarmth)
        }

        if (config.isCinematicFilmTextureEnabled) {
            renderCinematicFilmTexture(canvas, w, h, timeMs, config.cinematicTextureAmount)
        }

        if (config.isCinematicVignetteEnabled) {
            renderCinematicVignette(canvas, w, h, config.cinematicVignetteAmount)
        }

        if (config.isCinematicLetterboxEnabled) {
            renderCinematicLetterbox(canvas, w, h)
        }

        return output
    }

    // --- 1. Keyframing Engine ---
    private data class InterpolatedTransform(
        val posX: Float = 0f,
        val posY: Float = 0f,
        val scale: Float = 1.0f,
        val rotation: Float = 0f,
        val rotX: Float = 0f,
        val rotY: Float = 0f,
        val opacity: Float = 1.0f
    )

    private fun evaluateKeyframeTransform(keyframes: List<AeKeyframe>, timeMs: Long): InterpolatedTransform {
        if (keyframes.isEmpty()) return InterpolatedTransform()
        if (keyframes.size == 1) {
            val k = keyframes[0]
            return InterpolatedTransform(k.posX, k.posY, k.scale, k.rotation, k.rotX, k.rotY, k.opacity)
        }

        val sorted = keyframes.sortedBy { it.timeMs }
        if (timeMs <= sorted.first().timeMs) {
            val k = sorted.first()
            return InterpolatedTransform(k.posX, k.posY, k.scale, k.rotation, k.rotX, k.rotY, k.opacity)
        }
        if (timeMs >= sorted.last().timeMs) {
            val k = sorted.last()
            return InterpolatedTransform(k.posX, k.posY, k.scale, k.rotation, k.rotX, k.rotY, k.opacity)
        }

        // Find surrounding keyframes
        var prev = sorted.first()
        var next = sorted.last()
        for (i in 0 until sorted.size - 1) {
            if (timeMs >= sorted[i].timeMs && timeMs <= sorted[i + 1].timeMs) {
                prev = sorted[i]
                next = sorted[i + 1]
                break
            }
        }

        val span = (next.timeMs - prev.timeMs).toFloat().coerceAtLeast(1f)
        val rawT = ((timeMs - prev.timeMs).toFloat() / span).coerceIn(0f, 1f)
        val t = applyEasing(rawT, prev.easeType)

        return InterpolatedTransform(
            posX = prev.posX + (next.posX - prev.posX) * t,
            posY = prev.posY + (next.posY - prev.posY) * t,
            scale = prev.scale + (next.scale - prev.scale) * t,
            rotation = prev.rotation + (next.rotation - prev.rotation) * t,
            rotX = prev.rotX + (next.rotX - prev.rotX) * t,
            rotY = prev.rotY + (next.rotY - prev.rotY) * t,
            opacity = prev.opacity + (next.opacity - prev.opacity) * t
        )
    }

    private fun applyEasing(t: Float, easeType: String): Float {
        return when (easeType) {
            "EASE_IN" -> t * t
            "EASE_OUT" -> sin(t * Math.PI.toFloat() / 2f)
            "EASY_EASE" -> t * t * (3f - 2f * t) // Smooth cubic hermite curve
            "BOUNCE" -> {
                if (t < 0.5f) {
                    2f * t * t
                } else {
                    -1f + (4f - 2f * t) * t
                }
            }
            "ELASTIC" -> {
                val p = 0.3f
                (Math.pow(2.0, (-10 * t).toDouble()) * sin(((t - p / 4) * (2 * Math.PI) / p)) + 1).toFloat()
            }
            else -> t // Linear
        }
    }

    // --- Masking & Rotoscoping (Shape Masks) ---
    private fun applyMaskClip(canvas: Canvas, w: Int, h: Int, mask: AeMask) {
        val path = Path()
        val l = mask.left * w
        val t = mask.top * h
        val r = mask.right * w
        val b = mask.bottom * h

        if (mask.shape == AeMaskShape.ELLIPSE) {
            path.addOval(RectF(l, t, r, b), Path.Direction.CW)
        } else {
            path.addRoundRect(RectF(l, t, r, b), mask.feather, mask.feather, Path.Direction.CW)
        }

        if (mask.isInverted) {
            val full = Path()
            full.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
            full.op(path, Path.Op.DIFFERENCE)
            canvas.clipPath(full)
        } else {
            canvas.clipPath(path)
        }
    }

    // --- Turbulent Displace (Liquify & Heatwave ripples) ---
    private fun renderTurbulentDisplace(
        canvas: Canvas,
        src: Bitmap,
        timeMs: Long,
        amount: Float,
        speed: Float,
        paint: Paint
    ) {
        val strips = 20
        val stripH = src.height / strips
        val w = src.width
        val t = (timeMs / 1000f) * speed * 3.5f

        for (i in 0 until strips) {
            val sy = i * stripH
            val sh = if (i == strips - 1) src.height - sy else stripH
            val srcRect = Rect(0, sy, w, sy + sh)
            val dx = (sin((sy / 35.0) + t) * amount).toFloat()
            val dstRect = RectF(dx, sy.toFloat(), w.toFloat() + dx, (sy + sh).toFloat())
            canvas.drawBitmap(src, srcRect, dstRect, paint)
        }
    }

    // --- CC Light Rays (Volumetric God Rays) ---
    private fun renderCcLightRays(
        canvas: Canvas,
        w: Int,
        h: Int,
        xFrac: Float,
        yFrac: Float,
        intensity: Float,
        length: Float,
        timeMs: Long
    ) {
        val cx = w * xFrac
        val cy = h * yFrac
        val numRays = 16
        val rotBase = (timeMs / 45f) % 360f
        val maxLen = (min(w, h) * (length / 50f)).coerceAtLeast(60f)

        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        for (i in 0 until numRays) {
            val angleDeg = rotBase + (i * (360f / numRays))
            val rad = Math.toRadians(angleDeg.toDouble())
            val rayLen = maxLen * (0.65f + 0.35f * sin(i * 1.8 + timeMs / 250.0).toFloat())
            val ex = cx + (cos(rad) * rayLen).toFloat()
            val ey = cy + (sin(rad) * rayLen).toFloat()

            rayPaint.strokeWidth = 6f + (intensity * 10f)
            rayPaint.shader = LinearGradient(
                cx, cy, ex, ey,
                Color.argb((intensity * 190).toInt().coerceIn(30, 240), 255, 230, 160),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(cx, cy, ex, ey, rayPaint)
        }

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            shader = RadialGradient(
                cx, cy, maxLen * 0.4f,
                intArrayOf(Color.argb((intensity * 255).toInt().coerceIn(80, 255), 255, 255, 230), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, maxLen * 0.4f, corePaint)
    }

    // --- CC Radial Fast Blur / Zoom Blur ---
    private fun renderZoomBlur(canvas: Canvas, src: Bitmap, amount: Float) {
        val steps = 3
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val zoomPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            alpha = (90 / steps).coerceIn(15, 60)
        }
        for (i in 1..steps) {
            val scale = 1.0f + (amount / 200f) * (i.toFloat() / steps)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)
            canvas.translate(-cx, -cy)
            canvas.drawBitmap(src, 0f, 0f, zoomPaint)
            canvas.restore()
        }
    }

    // --- Saber Laser & Neon Edge ---
    private fun renderSaberEdgeGlow(canvas: Canvas, src: Bitmap, glowColor: Int, width: Float, timeMs: Long) {
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glowColor
            style = Paint.Style.STROKE
            strokeWidth = width
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            setShadowLayer(width * 2.5f, 0f, 0f, glowColor)
        }
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val inset = 10f
        canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), 16f, 16f, edgePaint)
    }

    // --- Lumetri Color Curves Grading ---
    private fun renderLumetriColor(canvas: Canvas, w: Int, h: Int, preset: String) {
        val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (preset) {
            "TEAL_ORANGE" -> {
                overlayPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                overlayPaint.shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    Color.argb(120, 255, 140, 20),
                    Color.argb(120, 0, 150, 180),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)
            }
            "CYBERPUNK" -> {
                overlayPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                overlayPaint.shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    Color.argb(130, 236, 72, 153),
                    Color.argb(130, 6, 182, 212),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)
            }
            "FILM_NOIR" -> {
                overlayPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                overlayPaint.color = Color.argb(100, 30, 30, 40)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)
            }
            "MATRIX" -> {
                overlayPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                overlayPaint.color = Color.argb(160, 50, 255, 120)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)
            }
            "VINTAGE_WARM" -> {
                overlayPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                overlayPaint.color = Color.argb(100, 250, 200, 100)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)
            }
        }
    }

    // --- 2. Camera Wiggle Shake (AE Expression: wiggle(freq, amp)) ---
    private fun evaluateCameraWiggle(timeMs: Long, freq: Float, amp: Float): PointF {
        val t = (timeMs / 1000f) * freq
        val dx = (sin(t * 3.1f) * 0.6f + cos(t * 7.3f) * 0.4f) * amp
        val dy = (cos(t * 2.7f) * 0.6f + sin(t * 6.1f) * 0.4f) * amp
        return PointF(dx, dy)
    }

    // --- 3. RGB Split & Cyberpunk Glitch ---
    private fun renderRgbGlitch(
        canvas: Canvas,
        src: Bitmap,
        timeMs: Long,
        intensity: Float,
        scanlines: Boolean,
        paint: Paint
    ) {
        val splitDist = (intensity * 24f * (0.8f + 0.4f * sin((timeMs / 100f).toDouble()).toFloat())).coerceIn(2f, 40f)

        // Draw Red offset
        val rPaint = Paint(paint).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0.7f, 0f
                )
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        canvas.drawBitmap(src, -splitDist, 0f, rPaint)

        // Draw Blue / Cyan offset
        val bPaint = Paint(paint).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, 0.7f, 0f
                )
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        canvas.drawBitmap(src, splitDist, 0f, bPaint)

        // Base image
        canvas.drawBitmap(src, 0f, 0f, paint)

        // Scanlines
        if (scanlines) {
            val linePaint = Paint().apply {
                color = Color.parseColor("#40000000")
                strokeWidth = 2f
            }
            var y = 0f
            while (y < src.height) {
                canvas.drawLine(0f, y, src.width.toFloat(), y, linePaint)
                y += 6f
            }
        }
    }

    // --- 4. Optical Deep Glow ---
    private fun renderOpticalGlow(
        canvas: Canvas,
        src: Bitmap,
        intensity: Float,
        radius: Float,
        glowColor: Int
    ) {
        val w = src.width
        val h = src.height

        // Downscale to create natural diffuse glow bloom
        val smallW = max(32, w / 4)
        val smallH = max(32, h / 4)
        val smallBmp = Bitmap.createScaledBitmap(src, smallW, smallH, true)

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            alpha = (intensity * 255).toInt().coerceIn(20, 255)
            colorFilter = android.graphics.PorterDuffColorFilter(glowColor, PorterDuff.Mode.SRC_ATOP)
        }

        canvas.drawBitmap(smallBmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), glowPaint)
        smallBmp.recycle()
    }

    // --- 5. Anamorphic Lens Flare ---
    private fun renderAnamorphicLensFlare(
        canvas: Canvas,
        w: Int,
        h: Int,
        flareNormX: Float,
        flareNormY: Float,
        intensity: Float,
        timeMs: Long
    ) {
        val cx = w * flareNormX
        val cy = h * flareNormY

        val pulse = 1.0f + 0.15f * sin((timeMs / 200f).toDouble()).toFloat()
        val flareW = w * 0.85f * pulse
        val flareH = 18f * pulse

        // Horizontal Anamorphic Streak
        val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            shader = LinearGradient(
                cx - flareW / 2f, cy,
                cx + flareW / 2f, cy,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#8006B6D4"), Color.WHITE, Color.parseColor("#8006B6D4"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(cx - flareW / 2f, cy - flareH / 2f, cx + flareW / 2f, cy + flareH / 2f, streakPaint)

        // Central Sun Hotspot
        val sunRadius = 70f * pulse * intensity
        val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            shader = RadialGradient(
                cx, cy, sunRadius,
                intArrayOf(Color.WHITE, Color.parseColor("#A0FBBF24"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, sunRadius, sunPaint)

        // Optical Flare Rings aligned along center axis
        val oppositeX = w - cx
        val oppositeY = h - cy
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            color = Color.parseColor("#4038BDF8")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(oppositeX * 0.7f + cx * 0.3f, oppositeY * 0.7f + cy * 0.3f, 45f, ringPaint)
        canvas.drawCircle(oppositeX * 0.4f + cx * 0.6f, oppositeY * 0.4f + cy * 0.6f, 25f, ringPaint)
    }

    // --- 6. Particle World Simulation (Natural Organic Multi-Directional Engine) ---
    private fun renderParticleWorld(
        canvas: Canvas,
        w: Int,
        h: Int,
        timeMs: Long,
        config: AeConfig
    ) {
        val type = config.activeParticleType ?: return
        if (type == AeEffectType.PARTICLE_FIREWORKS) {
            renderRealisticFireworks(canvas, w, h, timeMs, config)
            return
        }
        val count = config.particleCount.coerceIn(10, 300)
        val globalSpeed = config.particleSpeed * config.effectSpeed
        val baseSize = config.particleSize
        val turbulence = config.particleTurbulence.coerceIn(0f, 3.5f)
        // Cache Paint outside loop — avoids GC pressure from creating 300 objects per frame
        val pPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (i in 0 until count) {
            val seed = (i * 2654435761L) xor 0x5DEECE66DL
            val hash0 = ((seed ushr 16 and 0xFFFF) % 10000) / 10000f
            val hash1 = ((seed ushr 8 and 0xFFFF) % 10000) / 10000f
            val hash2 = ((seed ushr 4 and 0xFFFF) % 10000) / 10000f
            val hash3 = (abs(seed % 10000)) / 10000f

            val indivSpeed = (0.55f + hash0 * 0.9f) * globalSpeed
            val indivSize = (0.6f + hash1 * 0.8f) * baseSize
            val t = (timeMs / 1000f) * indivSpeed

            // Natural individual lifecycle & fading — smoothstep for ultra-smooth in/out
            val cycleSec = 3.2f / indivSpeed.coerceAtLeast(0.2f)
            val phase = ((t + hash1 * cycleSec) % cycleSec) / cycleSec
            // Smoothstep replaces raw sin: eliminates abrupt jumps at cycle boundaries
            val fadeInOut = smoothstep(0f, 0.2f, phase) * (1f - smoothstep(0.75f, 1f, phase))

            // Twinkle pulsation — smooth cosine-based, no hard clipping
            val twinkleFreq = config.particleTwinkleRate * (2.0f + hash0 * 3.5f)
            val twinkle = (cos(t * twinkleFreq + hash2 * 6.28f) * 0.3f + 0.7f).toFloat().coerceIn(0.1f, 1f)
            val curAlpha = (fadeInOut * twinkle * 255).toInt().coerceIn(0, 255)

            // Base spawn coordinate
            val origX = hash2 * w
            val origY = hash3 * h

            // Physics-based Organic Trajectory Calculation
            val posX: Float
            val posY: Float
            when (config.particleDirectionMode) {
                "RADIAL_BURST" -> {
                    val burstAngle = (hash0 * 2f * Math.PI.toFloat()) + (sin(t * 0.5f + hash1) * 0.4f * turbulence)
                    val dist = phase * max(w, h) * 0.65f
                    val rx = ((w * 0.5f) + cos(burstAngle) * dist)
                    val ry = ((h * 0.5f) + sin(burstAngle) * dist)
                    posX = (rx % w + w) % w
                    posY = (ry % h + h) % h
                }
                "UPWARD_FLOAT" -> {
                    val sway = sin(t * 1.6f * turbulence + hash0 * 6.28f) * 50f + cos(t * 0.8f) * 20f
                    val rx = origX + sway
                    val ry = h - (phase * (h + 100f))
                    posX = (rx % w + w) % w
                    posY = (ry % h + h) % h
                }
                "DOWNWARD_DRIFT" -> {
                    val sway = sin(t * 1.4f * turbulence + hash1 * 6.28f) * 40f
                    val rx = origX + sway + t * 25f
                    val ry = phase * (h + 100f)
                    posX = (rx % w + w) % w
                    posY = (ry % h + h) % h
                }
                "VORTEX_SWIRL" -> {
                    val vortexRadius = 30f + phase * max(w, h) * 0.45f
                    val vortexAngle = t * 2.2f + hash0 * 6.28f
                    val rx = ((w * 0.5f) + cos(vortexAngle) * vortexRadius)
                    val ry = ((h * 0.5f) + sin(vortexAngle) * vortexRadius)
                    posX = (rx % w + w) % w
                    posY = (ry % h + h) % h
                }
                else -> {
                    // RANDOM_WANDER: Brownian chaotic drifting with dual harmonic frequencies
                    val wanderX = sin(t * 0.75f + hash0 * 6.28f) * 65f * turbulence +
                                  cos(t * 1.4f + hash1 * 3.14f) * 35f * turbulence
                    val wanderY = cos(t * 0.65f + hash2 * 6.28f) * 55f * turbulence +
                                  sin(t * 1.25f + hash3 * 3.14f) * 25f * turbulence
                    val driftX = (hash0 - 0.5f) * 45f * t
                    val driftY = -t * 22f
                    val rx = origX + wanderX + driftX
                    val ry = origY + wanderY + driftY
                    posX = (rx % w + w) % w
                    posY = (ry % h + h) % h
                }
            }

            // Visual Styling per Particle Type
            when (type) {
                AeEffectType.PARTICLE_BLING -> {
                    val pColor = getParticlePaletteColor(config.particleColorPalette, seed, timeMs)
                    val baseR = (5f + hash2 * 7.5f) * indivSize
                    pPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                    pPaint.color = pColor
                    pPaint.alpha = (curAlpha * 0.85f).toInt()
                    canvas.drawCircle(posX, posY, baseR * 0.85f, pPaint)
                    if (config.isParticleStarGlintEnabled) {
                        val rot = (timeMs * 0.08f * indivSpeed + hash0 * 360f) % 360f
                        drawStarGlint(canvas, posX, posY, baseR * 1.4f, pColor, curAlpha, rot)
                    }
                }
                AeEffectType.PARTICLE_FIREFLIES -> {
                    val pColor = if (config.particleColorPalette == "MULTI") {
                        getParticlePaletteColor("FIREFLY", seed, timeMs)
                    } else {
                        getParticlePaletteColor(config.particleColorPalette, seed, timeMs)
                    }
                    val haloR = (18f + hash2 * 14f) * indivSize
                    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                        shader = RadialGradient(
                            posX, posY, haloR,
                            intArrayOf(
                                pColor,
                                Color.argb((curAlpha * 0.4f).toInt(), Color.red(pColor), Color.green(pColor), Color.blue(pColor)),
                                Color.TRANSPARENT
                            ),
                            floatArrayOf(0f, 0.45f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawCircle(posX, posY, haloR, glowPaint)
                    val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                        color = Color.WHITE
                        alpha = curAlpha
                    }
                    canvas.drawCircle(posX, posY, (2.6f + hash1 * 2f) * indivSize, corePaint)
                }
                AeEffectType.PARTICLE_STARS -> {
                    val pColor = getParticlePaletteColor(config.particleColorPalette, seed, timeMs)
                    val starR = (3.5f + hash0 * 4.5f) * indivSize
                    drawStarGlint(canvas, posX, posY, starR, pColor, curAlpha, hash1 * 90f)

                    // Shooting star meteor streak
                    if (i == 0) {
                        val cometPeriod = 4200L
                        val cometTime = (timeMs % cometPeriod).toFloat() / cometPeriod
                        if (cometTime < 0.28f) {
                            val cPhase = cometTime / 0.28f
                            val startCX = w * 0.88f - cPhase * (w * 0.75f)
                            val startCY = h * 0.08f + cPhase * (h * 0.38f)
                            val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                strokeWidth = 3.5f * indivSize
                                strokeCap = Paint.Cap.ROUND
                                shader = LinearGradient(
                                    startCX, startCY, startCX + 100f, startCY - 50f,
                                    Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP
                                )
                            }
                            canvas.drawLine(startCX, startCY, startCX + 100f, startCY - 50f, cometPaint)
                            drawStarGlint(canvas, startCX, startCY, 7.5f * indivSize, Color.WHITE, 255)
                        }
                    }
                }
                AeEffectType.PARTICLE_GOLD_DUST -> {
                    val goldColor = getParticlePaletteColor("GOLD", seed, timeMs)
                    val flakeR = (4.5f + hash3 * 6.5f) * indivSize
                    val rot = (timeMs * 0.06f + hash2 * 180f)
                    drawStarGlint(canvas, posX, posY, flakeR * 1.15f, goldColor, curAlpha, rot)
                }
                AeEffectType.PARTICLE_MAGIC_DUST -> {
                    val magicColor = getParticlePaletteColor(config.particleColorPalette, seed, timeMs)
                    val magicR = (4f + hash1 * 5.5f) * indivSize
                    drawStarGlint(canvas, posX, posY, magicR, magicColor, curAlpha, (t * 60f + hash0 * 360f))
                }
                AeEffectType.PARTICLE_SPARKS -> {
                    val sparkLife = phase
                    val sparkY = (h - (sparkLife * h * 1.15f)).let { (it % h + h) % h }
                    val sparkSize = (1f - sparkLife) * 7.5f * indivSize
                    val sparkColor = if (sparkLife < 0.35f) Color.parseColor("#FEF08A")
                                     else if (sparkLife < 0.7f) Color.parseColor("#F59E0B")
                                     else Color.parseColor("#EF4444")
                    pPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                    pPaint.color = sparkColor
                    pPaint.alpha = curAlpha
                    canvas.drawCircle(posX, sparkY, sparkSize, pPaint)
                }
                AeEffectType.PARTICLE_RAIN -> {
                    val rainY = (origY + t * 480f).let { (it % h + h) % h }
                    val rainX = (origX - (rainY * 0.22f)).let { (it % w + w) % w }
                    val dropLen = (22f + hash0 * 25f) * indivSize
                    val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#80E2E8F0")
                        strokeWidth = (1.5f + hash1 * 1.5f) * indivSize
                        strokeCap = Paint.Cap.ROUND
                    }
                    canvas.drawLine(rainX, rainY, rainX - (dropLen * 0.22f), rainY + dropLen, rainPaint)
                }
                AeEffectType.PARTICLE_SNOW -> {
                    val snowR = (2.5f + hash2 * 4f) * indivSize
                    pPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                    pPaint.color = Color.WHITE
                    pPaint.alpha = (90 + hash0 * 150).toInt()
                    canvas.drawCircle(posX, posY, snowR, pPaint)
                }
                AeEffectType.PARTICLE_BOKEH -> {
                    val bokehColor = getParticlePaletteColor(config.particleColorPalette, seed, timeMs)
                    val bokehR = (22f + hash3 * 38f) * indivSize
                    pPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                    pPaint.color = bokehColor
                    pPaint.alpha = (25 + hash1 * 40).toInt()
                    canvas.drawCircle(posX, posY, bokehR, pPaint)
                    pPaint.style = Paint.Style.STROKE
                    pPaint.strokeWidth = 2f
                    pPaint.alpha = (40 + hash1 * 50).toInt()
                    canvas.drawCircle(posX, posY, bokehR, pPaint)
                    pPaint.style = Paint.Style.FILL
                }
                AeEffectType.PARTICLE_FIREWORKS -> {
                    // Handled exclusively by renderRealisticFireworks for hyper-realistic physics & wide spread
                }
                AeEffectType.PARTICLE_KETUPAT -> {
                    // Eid Mubarak Festive Ketupat: Woven emerald-gold diamond with fluttering janur ribbon tails
                    val ketupatSize = (16f + hash0 * 16f) * indivSize
                    val swayAngle = sin(t * 1.6f + hash1 * 6.28f) * 16f

                    canvas.save()
                    canvas.translate(posX, posY)
                    canvas.rotate(swayAngle)

                    // Diamond body path (rotated woven shape)
                    val diamondW = ketupatSize * 0.88f
                    val diamondH = ketupatSize
                    val diamondPath = Path().apply {
                        moveTo(0f, -diamondH)
                        lineTo(diamondW, 0f)
                        lineTo(0f, diamondH)
                        lineTo(-diamondW, 0f)
                        close()
                    }

                    // Emerald-gold gradient body fill
                    val ketupatFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        shader = LinearGradient(
                            -diamondW, -diamondH, diamondW, diamondH,
                            intArrayOf(
                                Color.parseColor("#34D399"), // Mint/Emerald
                                Color.parseColor("#059669"), // Deep Green
                                Color.parseColor("#065F46")  // Forest
                            ),
                            floatArrayOf(0f, 0.55f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        alpha = curAlpha
                    }
                    canvas.drawPath(diamondPath, ketupatFill)

                    // Woven janur criss-cross texture (Anyaman Ketupat)
                    val weavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 1.6f * indivSize
                        color = Color.parseColor("#FBBF24") // Emas Janur
                        alpha = (curAlpha * 0.75f).toInt()
                    }
                    // Diagonals
                    canvas.drawLine(-diamondW * 0.5f, -diamondH * 0.5f, diamondW * 0.5f, diamondH * 0.5f, weavePaint)
                    canvas.drawLine(-diamondW * 0.5f, diamondH * 0.5f, diamondW * 0.5f, -diamondH * 0.5f, weavePaint)
                    // Mid lines
                    canvas.drawLine(0f, -diamondH * 0.85f, 0f, diamondH * 0.85f, weavePaint)
                    canvas.drawLine(-diamondW * 0.8f, 0f, diamondW * 0.8f, 0f, weavePaint)

                    // Outer golden border
                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 2.0f * indivSize
                        color = Color.parseColor("#FDE047") // Gold border
                        alpha = curAlpha
                    }
                    canvas.drawPath(diamondPath, borderPaint)

                    // Fluttering Coconut Ribbon Tails (Pita Janur Kelapa)
                    val tailLen = ketupatSize * 1.6f
                    val tailWave1 = sin(t * 3.6f + hash2 * 6.28f) * (7f * indivSize)
                    val tailWave2 = cos(t * 3.2f + hash3 * 6.28f) * (7f * indivSize)

                    val ribbonPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 2.4f * indivSize
                        strokeCap = Paint.Cap.ROUND
                        color = Color.parseColor("#34D399")
                        alpha = (curAlpha * 0.9f).toInt()
                    }
                    val ribbon1 = Path().apply {
                        moveTo(-2.5f * indivSize, diamondH)
                        quadTo(-6f * indivSize + tailWave1, diamondH + tailLen * 0.5f, -3f * indivSize + tailWave1 * 1.3f, diamondH + tailLen)
                    }
                    canvas.drawPath(ribbon1, ribbonPaint1)

                    val ribbonPaint2 = Paint(ribbonPaint1).apply {
                        color = Color.parseColor("#FBBF24") // Ribbon gold
                    }
                    val ribbon2 = Path().apply {
                        moveTo(2.5f * indivSize, diamondH)
                        quadTo(6f * indivSize + tailWave2, diamondH + tailLen * 0.5f, 4f * indivSize + tailWave2 * 1.4f, diamondH + tailLen * 1.15f)
                    }
                    canvas.drawPath(ribbon2, ribbonPaint2)

                    canvas.restore()

                    // Eid blessing star sparkles
                    if (i % 2 == 0) {
                        val sparkleAngle = t * 1.8f + hash0 * 6.28f
                        val spX = posX + cos(sparkleAngle) * (ketupatSize * 1.3f)
                        val spY = posY + sin(sparkleAngle) * (ketupatSize * 1.3f)
                        drawStarGlint(canvas, spX, spY, 4.5f * indivSize, Color.parseColor("#FDE047"), (curAlpha * 0.85f).toInt())
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Renders hyper-realistic, grand celebration aerial fireworks (pyrotechnics).
     * Features:
     * - Multi-shell staggered choreography (3 to 8 simultaneous bursting shells)
     * - Grand screen-filling radial spread (35% to 55% of min screen dimension)
     * - Ballistic physics: instantaneous explosive expansion with aerodynamic drag deceleration
     * - Weeping willow / Kamuro gravity curvature (quadratic drop t^2)
     * - Real luminous motion streaks / comet tails (tapered lines, NOT cartoon dots)
     * - Multi-tier shell construction: Chrysanthemum/Peony outer sphere + contrasting inner golden pistil
     * - High-temperature incandescent core (blazing white-hot center + colored halation)
     * - Secondary crackling stars ("Dragon Eggs" / popping time-rain) at late burst phase
     * - Ascending rocket launch with fiery exhaust and drifting embers
     */
    private fun renderRealisticFireworks(
        canvas: Canvas,
        w: Int,
        h: Int,
        timeMs: Long,
        config: AeConfig
    ) {
        val minDim = min(w, h).toFloat()
        val globalSpeed = (config.particleSpeed * config.effectSpeed).coerceIn(0.2f, 3.0f)
        val tSec = timeMs / 1000f

        // Total concurrent shells active in the sky based on particle count
        val totalShells = when {
            config.particleCount <= 35 -> 3
            config.particleCount <= 90 -> 5
            config.particleCount <= 160 -> 6
            else -> 8
        }

        // Each shell has a full lifecycle of ~3.4 seconds (launch -> flash -> burst -> willow droop)
        val cycleSec = 3.4f / globalSpeed
        val sparksPerShell = when {
            config.particleCount <= 35 -> 55
            config.particleCount <= 90 -> 75
            else -> 95
        }

        // Shared paint objects to eliminate GC pressure
        val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }

        // Professional chemical pyrotechnic palettes
        val shellPalettes = arrayOf(
            // 0: Shimmering Gold Kamuro (Weeping Willow)
            intArrayOf(Color.parseColor("#FFFBEB"), Color.parseColor("#FDE047"), Color.parseColor("#EAB308"), Color.parseColor("#B45309")),
            // 1: Strontium Ruby Crimson + White
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#FF2D55"), Color.parseColor("#E11D48"), Color.parseColor("#BE123C")),
            // 2: Copper Turquoise & Electric Cyan + Lemon Core
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#00F0FF"), Color.parseColor("#06B6D4"), Color.parseColor("#0284C7")),
            // 3: Barium Emerald + Golden Halation
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#10B981"), Color.parseColor("#059669"), Color.parseColor("#047857")),
            // 4: Royal Violet / Cesium Magenta + Amber
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#C084FC"), Color.parseColor("#A855F7"), Color.parseColor("#7E22CE")),
            // 5: Radiant Tangerine / Sodium Sunset
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#FB923C"), Color.parseColor("#F97316"), Color.parseColor("#C2410C")),
            // 6: Pure Magnesium Silver Sparklers
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), Color.parseColor("#94A3B8"))
        )

        for (s in 0 until totalShells) {
            // Stagger each shell evenly so sky is constantly alive with action
            val stagger = (s.toFloat() / totalShells) * cycleSec
            val totalTime = tSec + stagger
            val cycleIndex = (totalTime / cycleSec).toLong()
            val shellTime = totalTime % cycleSec
            val progress = shellTime / cycleSec // 0.0 to 1.0

            // Deterministic hash based on cycle index and shell slot
            val shellSeed = (cycleIndex * 6364136223846793005L + s * 1442695040888963407L)
            val h0 = ((shellSeed ushr 16 and 0xFFFF) % 10000) / 10000f
            val h1 = ((shellSeed ushr 8 and 0xFFFF) % 10000) / 10000f
            val h2 = ((shellSeed ushr 4 and 0xFFFF) % 10000) / 10000f
            val h3 = (abs(shellSeed % 10000)) / 10000f

            // Detonation altitude and position (high in the sky, 14% to 48% from top, 14% to 86% across)
            val burstCenterX = (0.14f + h0 * 0.72f) * w
            val burstCenterY = (0.14f + h1 * 0.34f) * h

            // Launch pad at bottom
            val launchPadX = burstCenterX + (h2 - 0.5f) * (w * 0.30f)
            val launchPadY = h.toFloat() + 30f

            // Choose color palette for this shell
            val activePalette = if (config.particleColorPalette != "MULTI") {
                val baseCol = getParticlePaletteColor(config.particleColorPalette, shellSeed, timeMs)
                intArrayOf(Color.WHITE, baseCol, baseCol, Color.parseColor("#F59E0B"))
            } else {
                shellPalettes[((s + cycleIndex.toInt()) % shellPalettes.size + shellPalettes.size) % shellPalettes.size]
            }

            // --- PHASE 1: Rocket Ascent (0.00 to 0.26) ---
            if (progress < 0.26f) {
                val lp = progress / 0.26f
                // Upward ballistic deceleration: fast takeoff, slows down at apex
                val easeY = 1f - (1f - lp) * (1f - lp)
                val curY = launchPadY - (launchPadY - burstCenterY) * easeY
                val curX = launchPadX + (burstCenterX - launchPadX) * lp + sin(lp * 3.14159f) * ((h3 - 0.5f) * 40f)

                // Previous point for rocket tail streak
                val prevLp = (lp - 0.045f).coerceAtLeast(0f)
                val prevEaseY = 1f - (1f - prevLp) * (1f - prevLp)
                val prevY = launchPadY - (launchPadY - burstCenterY) * prevEaseY
                val prevX = launchPadX + (burstCenterX - launchPadX) * prevLp + sin(prevLp * 3.14159f) * ((h3 - 0.5f) * 40f)

                // Draw luminous fiery rocket propulsion line
                streakPaint.strokeWidth = 3.6f * config.particleSize
                streakPaint.color = Color.parseColor("#FDE047")
                streakPaint.alpha = 230
                canvas.drawLine(prevX, prevY, curX, curY, streakPaint)

                // Blazing rocket motor head
                corePaint.color = Color.WHITE
                corePaint.alpha = 255
                canvas.drawCircle(curX, curY, 4.0f * config.particleSize, corePaint)

                // Golden trailing sparks behind ascending rocket
                for (trailSpark in 1..6) {
                    val dropDist = trailSpark * (14f * config.particleSize)
                    val sparkWobble = sin(tSec * 25f + trailSpark * 2.1f) * (4.5f * config.particleSize)
                    val sY = curY + dropDist
                    val sX = curX + sparkWobble
                    val sparkAlpha = ((1f - trailSpark / 7f) * 200).toInt()
                    corePaint.color = Color.parseColor("#F59E0B")
                    corePaint.alpha = sparkAlpha
                    canvas.drawCircle(sX, sY, (2.2f - trailSpark * 0.25f).coerceAtLeast(0.8f) * config.particleSize, corePaint)
                }

                continue
            }

            // --- PHASE 2: Detonation Flash (0.26 to 0.33) ---
            val burstP = ((progress - 0.26f) / 0.74f).coerceIn(0f, 1f)

            if (burstP < 0.12f) {
                val flashFrac = burstP / 0.12f
                val flashAlpha = ((1f - flashFrac) * 240).toInt().coerceIn(0, 255)
                val flashRadius = (35f + flashFrac * 160f) * config.particleSize * (minDim / 720f).coerceAtLeast(0.7f)

                // Intense white-hot core flash
                flashPaint.shader = RadialGradient(
                    burstCenterX, burstCenterY, flashRadius,
                    intArrayOf(Color.WHITE, activePalette[1], Color.TRANSPARENT),
                    floatArrayOf(0f, 0.32f, 1f),
                    Shader.TileMode.CLAMP
                )
                flashPaint.alpha = flashAlpha
                canvas.drawCircle(burstCenterX, burstCenterY, flashRadius, flashPaint)
                flashPaint.shader = null

                // Ambient night sky illumination (gives realistic atmospheric radiance)
                val ambientRadius = flashRadius * 3.2f
                flashPaint.shader = RadialGradient(
                    burstCenterX, burstCenterY, ambientRadius,
                    intArrayOf(Color.argb((flashAlpha * 0.35f).toInt(), Color.red(activePalette[1]), Color.green(activePalette[1]), Color.blue(activePalette[1])), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(burstCenterX, burstCenterY, ambientRadius, flashPaint)
                flashPaint.shader = null
            }

            // --- PHASE 3: Massive Aerial Radial Burst & Weeping Willow Embers (0.26 to 1.0) ---
            // Ballistic expansion curve: high initial outward snap, followed by air drag deceleration
            val expansion = 1f - (1f - burstP).let { it * it * (it * 0.6f + 0.4f) }
            val trailDelay = (0.05f * (1f - burstP * 0.7f)).coerceIn(0.015f, 0.055f)
            val prevBurstP = (burstP - trailDelay).coerceAtLeast(0f)
            val prevExpansion = 1f - (1f - prevBurstP).let { it * it * (it * 0.6f + 0.4f) }

            // Maximum expansion radius: PERCIKAN LEBAR!
            // Spans 35% to 54% of min screen dimension * particleSize (e.g. 380px to 620px radius on 1080p!)
            val maxSpreadRadius = minDim * (0.35f + h1 * 0.19f) * config.particleSize.coerceIn(0.6f, 2.5f)

            // Natural gravity drop: progressive quadratic curve (t^2)
            val gravityFall = (burstP * burstP) * (minDim * 0.28f) * (0.85f + h3 * 0.30f)
            val prevGravityFall = (prevBurstP * prevBurstP) * (minDim * 0.28f) * (0.85f + h3 * 0.30f)

            // Fade out factor towards the end of lifespan
            val fadeFactor = (1f - smoothstep(0.70f, 1.0f, burstP))
            val globalAlpha = (fadeFactor * 255).toInt().coerceIn(0, 255)
            if (globalAlpha <= 5) continue

            // Render all sparks in this shell
            for (k in 0 until sparksPerShell) {
                val sparkSeed = shellSeed xor (k * 2246822519L)
                val sh0 = ((sparkSeed ushr 16 and 0xFFFF) % 10000) / 10000f
                val sh1 = ((sparkSeed ushr 8 and 0xFFFF) % 10000) / 10000f
                val sh2 = ((sparkSeed ushr 4 and 0xFFFF) % 10000) / 10000f

                // Spherical angle distribution with slight natural angular jitter
                val baseAngle = (k.toFloat() / sparksPerShell) * 2f * 3.14159265f
                val angle = baseAngle + (sh0 - 0.5f) * 0.25f

                // Multi-tier velocity distribution:
                // 70% outer chrysanthemum stars (full speed)
                // 20% inner core pistil stars (55% speed with contrasting color)
                // 10% trailing willow dust (40% speed, heavy gravity)
                val isPistilCore = (k % 5 == 0)
                val isWillowTrail = (k % 7 == 0)
                val velMult = when {
                    isPistilCore -> 0.45f + sh1 * 0.22f
                    isWillowTrail -> 0.30f + sh1 * 0.25f
                    else -> 0.82f + sh1 * 0.30f
                }

                // 3D perspective distortion (slight vertical foreshortening creates volumetric 3D spherical look)
                val curDist = maxSpreadRadius * velMult * expansion
                val prevDist = maxSpreadRadius * velMult * prevExpansion

                val curX = burstCenterX + cos(angle) * curDist + (sh2 - 0.5f) * 16f * burstP
                val curY = burstCenterY + sin(angle) * (curDist * 0.86f) + gravityFall * (if (isWillowTrail) 1.35f else 1.0f)

                val prevX = burstCenterX + cos(angle) * prevDist
                val prevY = burstCenterY + sin(angle) * (prevDist * 0.86f) + prevGravityFall * (if (isWillowTrail) 1.35f else 1.0f)

                // Pyrotechnic color selection
                val sparkColor = when {
                    isPistilCore -> Color.parseColor("#FDE047") // Gold center pistil
                    isWillowTrail -> Color.parseColor("#F59E0B") // Amber willow trail
                    else -> activePalette[(k % (activePalette.size - 1)) + 1]
                }

                // High-temperature sparkling flicker (incandescent twinkling scintillation)
                val flicker = (cos(timeMs * 0.045f * config.particleTwinkleRate + k * 13.7f) * 0.25f + 0.75f).coerceIn(0.1f, 1f)
                val curSparkAlpha = (globalAlpha * flicker).toInt().coerceIn(0, 255)

                if (curSparkAlpha <= 5) continue

                // 1. Draw luminous velocity streak (motion trail!)
                streakPaint.color = sparkColor
                streakPaint.alpha = (curSparkAlpha * 0.85f).toInt()
                val streakWidth = (3.2f * (1f - burstP * 0.65f) + 0.9f) * config.particleSize
                streakPaint.strokeWidth = streakWidth
                canvas.drawLine(prevX, prevY, curX, curY, streakPaint)

                // 2. Draw incandescent burning star head (hot white-gold core + outer emission)
                corePaint.color = Color.WHITE
                corePaint.alpha = curSparkAlpha
                val coreRadius = (2.2f * (1f - burstP * 0.5f) + 0.8f) * config.particleSize
                canvas.drawCircle(curX, curY, coreRadius, corePaint)

                // 3. Secondary Crackling Stars ("Dragon Eggs" / Popping time-rain at late phase)
                if (burstP > 0.62f && (k % 3 == 0)) {
                    val crackleTime = (timeMs * 0.07f + k * 23.3f)
                    val cracklePulse = sin(crackleTime)
                    if (cracklePulse > 0.45f) {
                        // Sharp brilliant micro-flash
                        val crackleAlpha = ((cracklePulse - 0.45f) / 0.55f * 255).toInt().coerceIn(0, 255)
                        corePaint.color = Color.parseColor("#FEF08A")
                        corePaint.alpha = crackleAlpha
                        val crackleR = (3.5f + (cracklePulse - 0.45f) * 4f) * config.particleSize
                        canvas.drawCircle(curX, curY, crackleR, corePaint)

                        // 4-point micro glint
                        if (config.isParticleStarGlintEnabled && cracklePulse > 0.8f) {
                            drawStarGlint(canvas, curX, curY, 4.5f * config.particleSize, Color.WHITE, crackleAlpha, crackleTime * 20f)
                        }
                    }
                }

                // 4. Lingering golden micro-droplets shedding along willow trail
                if (isWillowTrail && burstP in 0.35f..0.85f && k % 2 == 0) {
                    val dropP = (sin(tSec * 15f + k.toFloat()) * 0.5f + 0.5f)
                    val dropX = prevX * dropP + curX * (1f - dropP)
                    val dropY = prevY * dropP + curY * (1f - dropP) + (dropP * 12f * config.particleSize)
                    corePaint.color = Color.parseColor("#FDE047")
                    corePaint.alpha = (curSparkAlpha * 0.65f).toInt()
                    canvas.drawCircle(dropX, dropY, 1.2f * config.particleSize, corePaint)
                }
            }
        }
    }

    // --- Star Glint 4-Point Diamond Flare Helper ---
    private fun drawStarGlint(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        alpha: Int,
        rotationAngle: Float = 0f
    ) {
        if (alpha <= 0 || radius <= 0.5f) return
        val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = alpha.coerceIn(0, 255)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            style = Paint.Style.FILL
        }

        canvas.save()
        canvas.translate(cx, cy)
        if (rotationAngle != 0f) {
            canvas.rotate(rotationAngle)
        }

        // Soft radial center orb
        val glowPaint = Paint(glintPaint).apply {
            shader = RadialGradient(
                0f, 0f, radius * 1.6f,
                intArrayOf(Color.WHITE, color, Color.TRANSPARENT),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(0f, 0f, radius * 1.6f, glowPaint)

        // Horizontal & Vertical Needle Rays
        val path = Path()
        val rX = radius * 3.0f
        val rY = radius * 0.28f
        path.moveTo(0f, -rY)
        path.lineTo(rX, 0f)
        path.lineTo(0f, rY)
        path.lineTo(-rX, 0f)
        path.close()

        path.moveTo(-rY, 0f)
        path.lineTo(0f, -rX)
        path.lineTo(rY, 0f)
        path.lineTo(0f, rX)
        path.close()
        canvas.drawPath(path, glintPaint)

        // Diagonal secondary rays (45 deg)
        val diagR = radius * 1.35f
        val diagThick = radius * 0.18f
        val diagPath = Path()
        diagPath.moveTo(-diagThick, -diagThick)
        diagPath.lineTo(diagR, -diagR)
        diagPath.lineTo(diagThick, diagThick)
        diagPath.lineTo(-diagR, diagR)
        diagPath.close()

        diagPath.moveTo(diagThick, -diagThick)
        diagPath.lineTo(-diagR, -diagR)
        diagPath.lineTo(-diagThick, diagThick)
        diagPath.lineTo(diagR, diagR)
        diagPath.close()

        glintPaint.alpha = (alpha * 0.65f).toInt().coerceIn(0, 255)
        canvas.drawPath(diagPath, glintPaint)

        // White hot center core
        glintPaint.color = Color.WHITE
        glintPaint.alpha = alpha
        canvas.drawCircle(0f, 0f, radius * 0.4f, glintPaint)

        canvas.restore()
    }

    // --- Particle Color Palette Resolver ---
    private fun getParticlePaletteColor(palette: String, seed: Long, timeMs: Long): Int {
        val goldPalette = intArrayOf(
            Color.parseColor("#FFD700"), Color.parseColor("#F59E0B"), Color.parseColor("#FBBF24"),
            Color.parseColor("#FEF08A"), Color.parseColor("#EAB308")
        )
        val diamondPalette = intArrayOf(
            Color.parseColor("#06B6D4"), Color.parseColor("#38BDF8"), Color.parseColor("#E0F2FE"),
            Color.parseColor("#818CF8"), Color.parseColor("#67E8F9")
        )
        val fireflyPalette = intArrayOf(
            Color.parseColor("#A3E635"), Color.parseColor("#FACC15"), Color.parseColor("#4ADE80"),
            Color.parseColor("#84CC16"), Color.parseColor("#FEF08A")
        )
        val rainbowPalette = intArrayOf(
            Color.parseColor("#EF4444"), Color.parseColor("#F59E0B"), Color.parseColor("#10B981"),
            Color.parseColor("#06B6D4"), Color.parseColor("#8B5CF6"), Color.parseColor("#EC4899")
        )
        val rubyPalette = intArrayOf(
            Color.parseColor("#EF4444"), Color.parseColor("#DC2626"), Color.parseColor("#F43F5E"), Color.parseColor("#FDA4AF")
        )
        val emeraldPalette = intArrayOf(
            Color.parseColor("#10B981"), Color.parseColor("#059669"), Color.parseColor("#34D399"), Color.parseColor("#6EE7B7")
        )
        val amethystPalette = intArrayOf(
            Color.parseColor("#9333EA"), Color.parseColor("#A855F7"), Color.parseColor("#C084FC"), Color.parseColor("#7E22CE")
        )
        val whitePalette = intArrayOf(
            Color.WHITE, Color.parseColor("#F8FAFC"), Color.parseColor("#E2E8F0"), Color.parseColor("#CBD5E1")
        )

        val arr = when (palette) {
            "GOLD" -> goldPalette
            "DIAMOND" -> diamondPalette
            "FIREFLY" -> fireflyPalette
            "RAINBOW" -> rainbowPalette
            "RUBY" -> rubyPalette
            "EMERALD" -> emeraldPalette
            "AMETHYST" -> amethystPalette
            "CRYSTAL_WHITE" -> whitePalette
            else -> {
                intArrayOf(
                    Color.parseColor("#FFD700"), Color.parseColor("#06B6D4"), Color.parseColor("#A3E635"),
                    Color.parseColor("#EC4899"), Color.parseColor("#8B5CF6"), Color.parseColor("#F59E0B"),
                    Color.parseColor("#38BDF8"), Color.WHITE
                )
            }
        }
        val idx = (abs(seed) % arr.size).toInt()
        return arr[idx]
    }

    // --- 7. 35mm Celluloid Film Grain ---
    private fun renderFilmGrain(canvas: Canvas, w: Int, h: Int, timeMs: Long, amount: Float) {
        val grainPaint = Paint().apply {
            color = Color.WHITE
            alpha = (amount * 45).toInt().coerceIn(5, 80)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        val step = 16
        val rand = Random(timeMs / 60)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (rand.nextBoolean()) {
                    canvas.drawPoint(x.toFloat() + rand.nextInt(step), y.toFloat() + rand.nextInt(step), grainPaint)
                }
                x += step
            }
            y += step
        }
    }

    // --- 8. Audio Waveform Spectrum Visualizer ---
    private fun renderAudioWaveform(canvas: Canvas, w: Int, h: Int, timeMs: Long) {
        val barCount = 48
        val barWidth = (w.toFloat() / barCount) * 0.7f
        val gap = (w.toFloat() / barCount) * 0.3f
        val bottomY = h * 0.92f

        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }

        for (i in 0 until barCount) {
            val freqOffset = i * 0.35f
            val t = (timeMs / 120f)
            val amp = (abs(sin((t + freqOffset).toDouble())) * 0.6f + abs(cos((t * 0.7f + freqOffset).toDouble())) * 0.4f).toFloat()
            val barHeight = 10f + amp * (h * 0.2f)

            val x = i * (barWidth + gap) + gap / 2f
            wavePaint.shader = LinearGradient(
                x, bottomY - barHeight, x, bottomY,
                Color.parseColor("#06B6D4"), Color.parseColor("#8B5CF6"),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(x, bottomY - barHeight, x + barWidth, bottomY, 6f, 6f, wavePaint)
        }
    }

    // --- 9. Motion Graphics Text Engine with Running Text, Frames, 3D/2D Styles & Typographies ---
    private fun renderMotionText(
        canvas: Canvas,
        w: Int,
        h: Int,
        timeMs: Long,
        item: AeMotionText,
        effectSpeed: Float
    ) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.color
            textSize = item.fontSize
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            alpha = (item.opacity * 255).toInt().coerceIn(0, 255)
            typeface = when (item.fontFamily) {
                "MONOSPACE" -> Typeface.MONOSPACE
                "SERIF" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                "SERIF_ITALIC" -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
                "SANS_SERIF" -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                "SANS_SERIF_BOLD" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                "CASUAL" -> Typeface.create("casual", Typeface.BOLD)
                "CURSIVE" -> Typeface.create("cursive", Typeface.BOLD)
                "COMIC" -> Typeface.create("sans-serif-black", Typeface.BOLD)
                "STENCIL" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
                else -> Typeface.DEFAULT_BOLD
            }
        }

        val px = w * item.x
        val py = h * item.y
        val speedMs = (timeMs * effectSpeed).toLong()
        val scrollSpeed = item.scrollSpeed.coerceIn(0.2f, 5.0f)

        when (item.style) {
            "SCROLL_LEFT" -> {
                // Teks Berjalan ke Kiri (TV News Ticker / Crawl)
                val textWidth = textPaint.measureText(item.text)
                val totalSpan = w + textWidth + 120f
                val traveled = ((speedMs * 0.16f * scrollSpeed) % totalSpan)
                val posX = (w + 60f) - traveled

                // Cinematic Optical Shutter Motion Blur
                val origAlpha = textPaint.alpha
                val blurOffset = (scrollSpeed * 3.0f).coerceIn(1.5f, 8.0f)
                textPaint.alpha = (origAlpha * 0.22f).toInt()
                drawStyledTextWithFrame(canvas, posX + blurOffset * 1.5f, py, item.text, textPaint, item, timeMs, w, h, drawFrame = false)
                drawStyledTextWithFrame(canvas, posX + blurOffset * 0.75f, py, item.text, textPaint, item, timeMs, w, h, drawFrame = false)
                textPaint.alpha = origAlpha

                drawStyledTextWithFrame(canvas, posX, py, item.text, textPaint, item, timeMs, w, h, drawFrame = true)
            }
            "SCROLL_RIGHT" -> {
                // Teks Berjalan ke Kanan (Marquee Right)
                val textWidth = textPaint.measureText(item.text)
                val totalSpan = w + textWidth + 120f
                val traveled = ((speedMs * 0.16f * scrollSpeed) % totalSpan)
                val posX = (-textWidth - 60f) + traveled

                val origAlpha = textPaint.alpha
                val blurOffset = (scrollSpeed * 3.0f).coerceIn(1.5f, 8.0f)
                textPaint.alpha = (origAlpha * 0.22f).toInt()
                drawStyledTextWithFrame(canvas, posX - blurOffset * 1.5f, py, item.text, textPaint, item, timeMs, w, h, drawFrame = false)
                drawStyledTextWithFrame(canvas, posX - blurOffset * 0.75f, py, item.text, textPaint, item, timeMs, w, h, drawFrame = false)
                textPaint.alpha = origAlpha

                drawStyledTextWithFrame(canvas, posX, py, item.text, textPaint, item, timeMs, w, h, drawFrame = true)
            }
            "SCROLL_UP" -> {
                // Teks Bergulir Naik (Movie End Credits Roll)
                val lines = item.text.split("\n")
                val totalTextHeight = lines.size * item.fontSize * 1.35f
                val totalSpan = h + totalTextHeight + 120f
                val traveled = ((speedMs * 0.08f * scrollSpeed) % totalSpan)
                val posY = (h + 60f) - traveled

                val origAlpha = textPaint.alpha
                val blurOffset = (scrollSpeed * 2.0f).coerceIn(1.0f, 5.0f)
                textPaint.alpha = (origAlpha * 0.22f).toInt()
                drawStyledTextWithFrame(canvas, px, posY + blurOffset, item.text, textPaint, item, timeMs, w, h, drawFrame = false)
                textPaint.alpha = origAlpha

                drawStyledTextWithFrame(canvas, px, posY, item.text, textPaint, item, timeMs, w, h, drawFrame = true)
            }
            "SCROLL_DOWN" -> {
                // Teks Bergulir Turun (Waterfall Down)
                val lines = item.text.split("\n")
                val totalTextHeight = lines.size * item.fontSize * 1.35f
                val totalSpan = h + totalTextHeight + 120f
                val traveled = ((speedMs * 0.08f * scrollSpeed) % totalSpan)
                val posY = (-totalTextHeight - 60f) + traveled

                val origAlpha = textPaint.alpha
                val blurOffset = (scrollSpeed * 2.0f).coerceIn(1.0f, 5.0f)
                textPaint.alpha = (origAlpha * 0.22f).toInt()
                drawStyledTextWithFrame(canvas, px, posY - blurOffset, item.text, textPaint, item, timeMs, w, h, drawFrame = false)
                textPaint.alpha = origAlpha

                drawStyledTextWithFrame(canvas, px, posY, item.text, textPaint, item, timeMs, w, h, drawFrame = true)
            }
            "BOUNCE_VERTICAL" -> {
                // Naik Turun Membal (Floating Up-Down)
                val bounce = sin((speedMs * 0.005f * scrollSpeed).toDouble()).toFloat() * 26f
                drawStyledTextWithFrame(canvas, px, py + bounce, item.text, textPaint, item, timeMs, w, h)
            }
            "BOUNCE_HORIZONTAL" -> {
                // Goyang Kiri Kanan (Swaying Left-Right)
                val sway = sin((speedMs * 0.004f * scrollSpeed).toDouble()).toFloat() * 32f
                drawStyledTextWithFrame(canvas, px + sway, py, item.text, textPaint, item, timeMs, w, h)
            }
            "TYPEWRITER" -> {
                val cycleMs = 3500L
                val t = (speedMs % cycleMs).toFloat() / cycleMs
                val charsToShow = (t * item.text.length * 1.3f).toInt().coerceIn(0, item.text.length)
                val substring = item.text.substring(0, charsToShow)
                drawStyledTextWithFrame(canvas, px, py, substring, textPaint, item, timeMs, w, h)
            }
            "KINETIC_ZOOM" -> {
                val cycleMs = 1500L
                val t = (speedMs % cycleMs).toFloat() / cycleMs
                val scale = if (t < 0.2f) {
                    0.5f + (t / 0.2f) * 0.7f // Pop in
                } else if (t < 0.35f) {
                    1.2f - ((t - 0.2f) / 0.15f) * 0.2f // Settle to 1.0
                } else {
                    1.0f
                }
                canvas.save()
                canvas.translate(px, py)
                canvas.scale(scale, scale)
                drawStyledTextWithFrame(canvas, 0f, 0f, item.text, textPaint, item, timeMs, w, h)
                canvas.restore()
            }
            "NEON_PULSE" -> {
                val pulse = 0.7f + 0.3f * sin((speedMs / 180f).toDouble()).toFloat()
                textPaint.alpha = (pulse * 255).toInt().coerceIn(80, 255)
                drawStyledTextWithFrame(canvas, px, py, item.text, textPaint, item, timeMs, w, h)
            }
            "GLITCH" -> {
                val glitchOffset = if (random.nextInt(10) > 7) (random.nextFloat() - 0.5f) * 16f else 0f
                canvas.save()
                canvas.translate(px + glitchOffset, py)
                textPaint.color = Color.parseColor("#EC4899")
                canvas.drawText(item.text, -4f, 0f, textPaint)
                textPaint.color = Color.parseColor("#06B6D4")
                canvas.drawText(item.text, 4f, 0f, textPaint)
                textPaint.color = item.color
                drawStyledTextWithFrame(canvas, 0f, 0f, item.text, textPaint, item, timeMs, w, h)
                canvas.restore()
            }
            "3D_FLIP" -> {
                val cycleMs = 2200L
                val t = (speedMs % cycleMs).toFloat() / cycleMs
                val rotY = if (t < 0.4f) (1f - (t / 0.4f)) * 90f else 0f
                val cam = Camera()
                cam.save()
                cam.rotateY(rotY)
                val mtx = Matrix()
                cam.getMatrix(mtx)
                cam.restore()
                canvas.save()
                canvas.translate(px, py)
                canvas.concat(mtx)
                drawStyledTextWithFrame(canvas, 0f, 0f, item.text, textPaint, item, timeMs, w, h)
                canvas.restore()
            }
            "WAVE_BOUNCE" -> {
                val bounce = sin((speedMs / 130f).toDouble()).toFloat() * 16f
                canvas.save()
                canvas.translate(px, py + bounce)
                drawStyledTextWithFrame(canvas, 0f, 0f, item.text, textPaint, item, timeMs, w, h)
                canvas.restore()
            }
            "FADE_SLIDE" -> {
                val cycleMs = 2500L
                val t = ((speedMs % cycleMs).toFloat() / cycleMs).coerceIn(0f, 1f)
                val slideY = if (t < 0.25f) (1f - (t / 0.25f)) * 35f else 0f
                textPaint.alpha = ((t.coerceIn(0f, 0.25f) / 0.25f) * 255).toInt()
                canvas.save()
                canvas.translate(px, py + slideY)
                drawStyledTextWithFrame(canvas, 0f, 0f, item.text, textPaint, item, timeMs, w, h)
                canvas.restore()
            }
            else -> {
                drawStyledTextWithFrame(canvas, px, py, item.text, textPaint, item, timeMs, w, h)
            }
        }
    }

    private fun drawStyledTextWithFrame(
        canvas: Canvas,
        x: Float,
        y: Float,
        text: String,
        textPaint: Paint,
        item: AeMotionText,
        timeMs: Long,
        w: Int,
        h: Int,
        drawFrame: Boolean = true
    ) {
        if (text.isEmpty()) return

        val lines = text.split("\n")
        val lineHeight = item.fontSize * 1.35f
        var maxLineWidth = 0f
        for (line in lines) {
            val lw = textPaint.measureText(line)
            if (lw > maxLineWidth) maxLineWidth = lw
        }
        val totalHeight = lines.size * lineHeight

        // 1. Gambar Template Frame / Bingkai di belakang teks
        if (drawFrame && item.frameStyle != "NONE") {
            drawTextFrame(canvas, x, y, maxLineWidth, totalHeight, item, w, h)
        }

        // 2. Gambar setiap baris teks sesuai Model Gaya Huruf (3D / 2D / Normal)
        lines.forEachIndexed { index, line ->
            val lineY = if (lines.size == 1) {
                y
            } else {
                y - (totalHeight / 2f) + (index * lineHeight) + (item.fontSize * 0.85f)
            }
            drawSingleTextLine(canvas, x, lineY, line, textPaint, item, timeMs)
        }
    }

    private fun drawTextFrame(
        canvas: Canvas,
        x: Float,
        y: Float,
        textWidth: Float,
        textHeight: Float,
        item: AeMotionText,
        w: Int,
        h: Int
    ) {
        val padH = item.framePaddingH
        val padV = item.framePaddingV
        val left = x - (textWidth / 2f) - padH
        val top = y - textHeight * 0.85f - padV
        val right = x + (textWidth / 2f) + padH
        val bottom = y + textHeight * 0.25f + padV
        val frameRect = RectF(left, top, right, bottom)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (item.frameBgColor != Color.TRANSPARENT) item.frameBgColor else Color.argb(190, 15, 23, 42)
            alpha = (item.frameOpacity * 255).toInt().coerceIn(0, 255)
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = item.frameBorderWidth.coerceAtLeast(1.5f)
            color = if (item.frameBorderColor != Color.TRANSPARENT) item.frameBorderColor else Color.WHITE
        }

        when (item.frameStyle) {
            "ROUNDED_BOX" -> {
                canvas.drawRoundRect(frameRect, item.frameCornerRadius, item.frameCornerRadius, bgPaint)
                if (item.frameBorderWidth > 0f) {
                    canvas.drawRoundRect(frameRect, item.frameCornerRadius, item.frameCornerRadius, borderPaint)
                }
            }
            "FULL_BANNER" -> {
                // Running Text Ticker Bar memanjang penuh dari kiri ke kanan layar
                val bannerRect = RectF(0f, top, w.toFloat(), bottom)
                canvas.drawRect(bannerRect, bgPaint)
                borderPaint.strokeWidth = 2.5f
                canvas.drawLine(0f, top, w.toFloat(), top, borderPaint)
                canvas.drawLine(0f, bottom, w.toFloat(), bottom, borderPaint)
            }
            "NEON_GLOW_BORDER" -> {
                canvas.drawRoundRect(frameRect, 16f, 16f, bgPaint)
                borderPaint.setShadowLayer(16f, 0f, 0f, borderPaint.color)
                canvas.drawRoundRect(frameRect, 16f, 16f, borderPaint)
            }
            "CYBERPUNK_CORNERS" -> {
                canvas.drawRect(frameRect, bgPaint)
                val arm = (item.fontSize * 0.45f).coerceAtLeast(14f)
                borderPaint.strokeWidth = 3.5f
                // Top-Left
                canvas.drawLine(left, top, left + arm, top, borderPaint)
                canvas.drawLine(left, top, left, top + arm, borderPaint)
                // Top-Right
                canvas.drawLine(right - arm, top, right, top, borderPaint)
                canvas.drawLine(right, top, right, top + arm, borderPaint)
                // Bottom-Left
                canvas.drawLine(left, bottom, left + arm, bottom, borderPaint)
                canvas.drawLine(left, bottom - arm, left, bottom, borderPaint)
                // Bottom-Right
                canvas.drawLine(right - arm, bottom, right, bottom, borderPaint)
                canvas.drawLine(right, bottom - arm, right, bottom, borderPaint)
            }
            "CINEMATIC_LETTERBOX" -> {
                bgPaint.color = Color.argb(220, 0, 0, 0)
                canvas.drawRect(frameRect, bgPaint)
                borderPaint.strokeWidth = 2f
                canvas.drawLine(left, top, right, top, borderPaint)
                canvas.drawLine(left, bottom, right, bottom, borderPaint)
            }
            "DOUBLE_BORDER_STAMP" -> {
                canvas.drawRoundRect(frameRect, 8f, 8f, bgPaint)
                canvas.drawRoundRect(frameRect, 8f, 8f, borderPaint)
                val innerRect = RectF(left + 6f, top + 6f, right - 6f, bottom - 6f)
                borderPaint.strokeWidth = 1.5f
                canvas.drawRoundRect(innerRect, 6f, 6f, borderPaint)
            }
            "ANGLED_RIBBON" -> {
                val slant = 22f
                val path = Path().apply {
                    moveTo(left - slant, bottom)
                    lineTo(left, top)
                    lineTo(right + slant, top)
                    lineTo(right, bottom)
                    close()
                }
                canvas.drawPath(path, bgPaint)
                if (item.frameBorderWidth > 0f) {
                    canvas.drawPath(path, borderPaint)
                }
            }
            "GRADIENT_GLASS" -> {
                val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        left, top, right, bottom,
                        intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(25, 255, 255, 255)),
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(frameRect, 18f, 18f, glassPaint)
                borderPaint.color = Color.argb(140, 255, 255, 255)
                borderPaint.strokeWidth = 1.5f
                canvas.drawRoundRect(frameRect, 18f, 18f, borderPaint)
            }
        }
    }

    private fun drawSingleTextLine(
        canvas: Canvas,
        x: Float,
        y: Float,
        line: String,
        textPaint: Paint,
        item: AeMotionText,
        timeMs: Long
    ) {
        if (line.isEmpty()) return

        when (item.textRenderMode) {
            "3D_ISOMETRIC" -> {
                val depth = item.embossDepth.coerceIn(2f, 25f)
                val shadowPaint = Paint(textPaint).apply {
                    color = item.shadowColor
                    style = Paint.Style.FILL
                    clearShadowLayer()
                }
                for (d in 1..depth.toInt()) {
                    canvas.drawText(line, x - d * 0.8f, y + d * 1.2f, shadowPaint)
                }
                if (item.strokeWidth > 0f) {
                    val strokePaint = Paint(textPaint).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = item.strokeWidth
                        color = item.strokeColor
                        clearShadowLayer()
                    }
                    canvas.drawText(line, x, y, strokePaint)
                }
                textPaint.setShadowLayer(item.shadowRadius, 0f, 0f, item.glowColor)
                canvas.drawText(line, x, y, textPaint)
            }
            "2D_FLAT" -> {
                textPaint.clearShadowLayer()
                if (item.strokeWidth > 0f) {
                    val strokePaint = Paint(textPaint).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = item.strokeWidth
                        color = item.strokeColor
                        clearShadowLayer()
                    }
                    canvas.drawText(line, x, y, strokePaint)
                }
                canvas.drawText(line, x, y, textPaint)
            }
            "2D_OUTLINE" -> {
                val strokePaint = Paint(textPaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = item.strokeWidth.coerceAtLeast(3.5f)
                    color = item.color
                    clearShadowLayer()
                }
                canvas.drawText(line, x, y, strokePaint)
            }
            "2D_DUAL_TONE" -> {
                val shadowPaint = Paint(textPaint).apply {
                    color = item.shadowColor
                    style = Paint.Style.FILL
                    clearShadowLayer()
                }
                canvas.drawText(line, x + 6f, y + 6f, shadowPaint)
                val strokePaint = Paint(textPaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (item.strokeWidth * 1.8f).coerceAtLeast(4.5f)
                    color = item.strokeColor
                    clearShadowLayer()
                }
                canvas.drawText(line, x, y, strokePaint)
                canvas.drawText(line, x, y, textPaint)
            }
            "2D_SOFT_SHADOW" -> {
                textPaint.setShadowLayer(16f, 0f, 8f, Color.argb(160, 0, 0, 0))
                canvas.drawText(line, x, y, textPaint)
            }
            else -> {
                // "3D_EMBOSS" (Default)
                if (item.isEmbossEnabled) {
                    val depth = item.embossDepth.coerceIn(1f, 20f)
                    val shadowPaint = Paint(textPaint).apply {
                        color = item.shadowColor
                        style = Paint.Style.FILL
                        clearShadowLayer()
                    }
                    for (d in 1..depth.toInt()) {
                        canvas.drawText(line, x + d, y + d, shadowPaint)
                    }
                    val highlightPaint = Paint(textPaint).apply {
                        color = Color.argb(100, 255, 255, 255)
                        style = Paint.Style.FILL
                        clearShadowLayer()
                    }
                    canvas.drawText(line, x - 1.5f, y - 1.5f, highlightPaint)
                }

                if (item.strokeWidth > 0f) {
                    val strokePaint = Paint(textPaint).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = item.strokeWidth
                        color = item.strokeColor
                        clearShadowLayer()
                    }
                    canvas.drawText(line, x, y, strokePaint)
                }

                textPaint.setShadowLayer(item.shadowRadius, 0f, 0f, item.glowColor)
                canvas.drawText(line, x, y, textPaint)
            }
        }

        // Shimmer / Metallic Sheen sweep melintasi teks
        if (item.isShimmerEnabled) {
            val textWidth = textPaint.measureText(line)
            val cycle = 1800L
            val speed = item.shimmerSpeed.coerceAtLeast(0.1f)
            val t = ((timeMs * speed) % cycle).toFloat() / cycle
            val startSweepX = x - (textWidth * 0.5f) - 100f
            val endSweepX = x + (textWidth * 0.5f) + 100f
            val currentBeamX = startSweepX + t * (endSweepX - startSweepX)

            val shimmerPaint = Paint(textPaint).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                clearShadowLayer()
                shader = LinearGradient(
                    currentBeamX - 60f, y - item.fontSize,
                    currentBeamX + 60f, y + 10f,
                    intArrayOf(
                        Color.TRANSPARENT,
                        item.shimmerColor,
                        Color.WHITE,
                        item.shimmerColor,
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            val textBounds = RectF(
                x - textWidth * 0.6f,
                y - item.fontSize * 1.5f,
                x + textWidth * 0.6f,
                y + item.fontSize * 0.8f
            )
            canvas.saveLayer(textBounds, null)
            canvas.drawText(line, x, y, textPaint)
            canvas.drawText(line, x, y, shimmerPaint)
            canvas.restore()
        }

        // Star Glint Sparkles (Kilau Bintang 4-Sudut di Sudut Huruf)
        if (item.isStarGlintEnabled) {
            val textWidth = textPaint.measureText(line)
            val leftX = x - (textWidth * 0.48f)
            val topY = y - (item.fontSize * 0.75f)

            val phase1 = (sin((timeMs * 0.006f) + 0f) * 0.5f + 0.5f)
            val rot1 = (timeMs * 0.08f) % 360f
            drawStarGlint(canvas, leftX + 8f, topY + 4f, 8f * phase1, item.starGlintColor, (phase1 * 255).toInt(), rot1)

            val phase2 = (sin((timeMs * 0.006f) + 2.2f) * 0.5f + 0.5f)
            val rot2 = (timeMs * -0.06f + 45f) % 360f
            drawStarGlint(canvas, leftX + textWidth - 8f, topY + 2f, 7.5f * phase2, item.starGlintColor, (phase2 * 255).toInt(), rot2)

            val phase3 = (sin((timeMs * 0.007f) + 4.4f) * 0.5f + 0.5f)
            val rot3 = (timeMs * 0.1f + 90f) % 360f
            drawStarGlint(canvas, x, topY - 3f, 6.5f * phase3, item.starGlintColor, (phase3 * 255).toInt(), rot3)
        }
    }

    // --- 10. Animated Sticker & Emoji Engine ---
    private fun renderSticker(
        canvas: Canvas,
        w: Int,
        h: Int,
        timeMs: Long,
        sticker: AeSticker,
        effectSpeed: Float
    ) {
        val px = w * sticker.x
        val py = h * sticker.y
        val speedMs = (timeMs * effectSpeed).toLong()

        var curScale = sticker.scale
        var curRot = sticker.rotation
        var offsetX = 0f
        var offsetY = 0f

        when (sticker.animationType) {
            "PULSE" -> {
                curScale *= (1.0f + 0.18f * sin(speedMs / 180.0).toFloat())
            }
            "BOUNCE" -> {
                offsetY = -abs(sin(speedMs / 140.0).toFloat()) * 28f
            }
            "SPIN" -> {
                curRot = (curRot + (speedMs / 8f)) % 360f
            }
            "WOBBLE" -> {
                curRot += sin(speedMs / 120.0).toFloat() * 16f
            }
            "FLOAT" -> {
                offsetX = sin(speedMs / 250.0).toFloat() * 14f
                offsetY = cos(speedMs / 200.0).toFloat() * 14f
            }
            else -> {}
        }

        canvas.save()
        canvas.translate(px + offsetX, py + offsetY)
        canvas.rotate(curRot)
        canvas.scale(curScale, curScale)

        val stickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 68f
            textAlign = Paint.Align.CENTER
            alpha = (sticker.opacity * 255).toInt().coerceIn(0, 255)
        }
        canvas.drawText(sticker.content, 0f, 24f, stickerPaint)
        canvas.restore()
    }

    // --- 10b. Menumpuk Video atau Foto (Media Overlay / PIP Engine) ---
    private fun renderMediaOverlay(
        canvas: Canvas,
        w: Int,
        h: Int,
        timeMs: Long,
        overlay: AeMediaOverlay
    ) {
        val bmp = overlay.cachedBitmap ?: return
        if (bmp.isRecycled) return

        // 1. Duration bounds & Transition Progress calculation
        val inElapsed = (timeMs - overlay.startTimeMs).coerceAtLeast(0L)
        val outRemaining = (overlay.endTimeMs - timeMs).coerceAtLeast(0L)
        val tDur = overlay.transitionDurationMs.coerceAtLeast(50L).toFloat()

        var curOpacity = overlay.opacity.coerceIn(0f, 1f)
        var curScale = overlay.scale.coerceIn(0.05f, 5.0f)
        var curRot = overlay.rotation
        var offsetX = 0f
        var offsetY = 0f

        when (overlay.transition) {
            AeOverlayTransition.NONE -> {
                // Hard cut, no transition effect
            }
            AeOverlayTransition.FADE -> {
                if (inElapsed < tDur) {
                    val p = (inElapsed / tDur).coerceIn(0f, 1f)
                    curOpacity *= smoothstep(0f, 1f, p)
                } else if (outRemaining < tDur) {
                    val p = (outRemaining / tDur).coerceIn(0f, 1f)
                    curOpacity *= smoothstep(0f, 1f, p)
                }
            }
            AeOverlayTransition.ZOOM -> {
                if (inElapsed < tDur) {
                    val p = (inElapsed / tDur).coerceIn(0f, 1f)
                    val s = smoothstep(0f, 1f, p)
                    curScale *= s
                    curOpacity *= s
                } else if (outRemaining < tDur) {
                    val p = (outRemaining / tDur).coerceIn(0f, 1f)
                    val s = smoothstep(0f, 1f, p)
                    curScale *= s
                    curOpacity *= s
                }
            }
            AeOverlayTransition.SLIDE_LEFT -> {
                if (inElapsed < tDur) {
                    val p = 1f - smoothstep(0f, 1f, (inElapsed / tDur).coerceIn(0f, 1f))
                    offsetX += p * w * 0.75f
                    curOpacity *= (1f - p * 0.5f)
                } else if (outRemaining < tDur) {
                    val p = 1f - smoothstep(0f, 1f, (outRemaining / tDur).coerceIn(0f, 1f))
                    offsetX -= p * w * 0.75f
                    curOpacity *= (1f - p * 0.5f)
                }
            }
            AeOverlayTransition.SLIDE_RIGHT -> {
                if (inElapsed < tDur) {
                    val p = 1f - smoothstep(0f, 1f, (inElapsed / tDur).coerceIn(0f, 1f))
                    offsetX -= p * w * 0.75f
                    curOpacity *= (1f - p * 0.5f)
                } else if (outRemaining < tDur) {
                    val p = 1f - smoothstep(0f, 1f, (outRemaining / tDur).coerceIn(0f, 1f))
                    offsetX += p * w * 0.75f
                    curOpacity *= (1f - p * 0.5f)
                }
            }
            AeOverlayTransition.SLIDE_UP -> {
                if (inElapsed < tDur) {
                    val p = 1f - smoothstep(0f, 1f, (inElapsed / tDur).coerceIn(0f, 1f))
                    offsetY += p * h * 0.75f
                    curOpacity *= (1f - p * 0.5f)
                } else if (outRemaining < tDur) {
                    val p = 1f - smoothstep(0f, 1f, (outRemaining / tDur).coerceIn(0f, 1f))
                    offsetY -= p * h * 0.75f
                    curOpacity *= (1f - p * 0.5f)
                }
            }
            AeOverlayTransition.SLIDE_DOWN -> {
                if (inElapsed < tDur) {
                    val p = 1f - smoothstep(0f, 1f, (inElapsed / tDur).coerceIn(0f, 1f))
                    offsetY -= p * h * 0.75f
                    curOpacity *= (1f - p * 0.5f)
                } else if (outRemaining < tDur) {
                    val p = 1f - smoothstep(0f, 1f, (outRemaining / tDur).coerceIn(0f, 1f))
                    offsetY += p * h * 0.75f
                    curOpacity *= (1f - p * 0.5f)
                }
            }
            AeOverlayTransition.SPIN -> {
                if (inElapsed < tDur) {
                    val p = (inElapsed / tDur).coerceIn(0f, 1f)
                    val s = smoothstep(0f, 1f, p)
                    curRot += (1f - s) * 360f
                    curScale *= s
                    curOpacity *= s
                } else if (outRemaining < tDur) {
                    val p = (outRemaining / tDur).coerceIn(0f, 1f)
                    val s = smoothstep(0f, 1f, p)
                    curRot -= (1f - s) * 360f
                    curScale *= s
                    curOpacity *= s
                }
            }
            AeOverlayTransition.BOUNCE -> {
                if (inElapsed < tDur) {
                    val t = (inElapsed / tDur).coerceIn(0f, 1f)
                    val bounce = if (t < 0.6f) {
                        (t / 0.6f) * 1.15f
                    } else {
                        1.15f - ((t - 0.6f) / 0.4f) * 0.15f
                    }
                    curScale *= bounce
                    curOpacity *= t
                } else if (outRemaining < tDur) {
                    val t = (outRemaining / tDur).coerceIn(0f, 1f)
                    curScale *= t
                    curOpacity *= t
                }
            }
        }

        if (curOpacity <= 0.005f || curScale <= 0.005f) return

        // 2. Position on composition canvas
        val px = w * overlay.x + offsetX
        val py = h * overlay.y + offsetY

        // 3. Render on canvas with scale, rotation, rounded corners, and border
        canvas.save()
        canvas.translate(px, py)
        canvas.rotate(curRot)
        canvas.scale(curScale, curScale)

        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        val left = -bmpW / 2f
        val top = -bmpH / 2f
        val right = bmpW / 2f
        val bottom = bmpH / 2f
        val rect = RectF(left, top, right, bottom)

        val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (curOpacity * 255).toInt().coerceIn(0, 255)
            xfermode = PorterDuffXfermode(overlay.blendMode.porterDuffMode)
        }

        if (overlay.isFlagWaveEnabled) {
            renderMediaOverlayFlagWave(canvas, bmp, left, top, bmpW, bmpH, timeMs, overlay, overlayPaint)
        } else if (overlay.cornerRadius > 0f) {
            val path = Path().apply {
                addRoundRect(rect, overlay.cornerRadius, overlay.cornerRadius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(bmp, left, top, overlayPaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, left, top, overlayPaint)
        }

        // Draw border if set
        if (overlay.borderWidth > 0f) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = overlay.borderWidth
                color = overlay.borderColor
                alpha = (curOpacity * 255).toInt().coerceIn(0, 255)
            }
            if (overlay.cornerRadius > 0f) {
                canvas.drawRoundRect(rect, overlay.cornerRadius, overlay.cornerRadius, borderPaint)
            } else {
                canvas.drawRect(rect, borderPaint)
            }
        }

        canvas.restore()
    }

    // --- 11. Unmult Alpha Keyer (Black/White Background Removal) ---
    private fun applyUnmult(canvas: Canvas, target: Bitmap, w: Int, h: Int, mode: String, threshold: Float) {
        val threshVal = (threshold * 255f)
        val matrix = if (mode == "WHITE") {
            // Invert luminance for white background
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                -0.3f, -0.59f, -0.11f, 0f, 255f - threshVal
            )
        } else {
            // Standard Knoll's Unmult: luminance determines alpha
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0.3f, 0.59f, 0.11f, 0f, -threshVal
            )
        }
        val unmultPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix))
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(target, 0f, 0f, unmultPaint)
    }

    // --- 12. Contour Paint (Cel-Shaded Outline Art) ---
    private fun applyContourPaint(canvas: Canvas, w: Int, h: Int, src: Bitmap, threshold: Float, lineWidth: Float) {
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            alpha = (threshold * 220).toInt().coerceIn(80, 255)
        }
        val step = 20
        for (y in step until (h - step) step step) {
            val path = Path()
            var isDrawing = false
            for (x in step until (w - step) step step) {
                val px = src.getPixel(x, y)
                val lum = (Color.red(px) * 0.3f + Color.green(px) * 0.59f + Color.blue(px) * 0.11f) / 255f
                if (lum < 0.30f || lum > 0.82f) {
                    if (!isDrawing) {
                        path.moveTo(x.toFloat(), y.toFloat())
                        isDrawing = true
                    } else {
                        path.lineTo(x.toFloat(), y.toFloat() + (sin(x * 0.12) * 3f).toFloat())
                    }
                } else {
                    isDrawing = false
                }
            }
            canvas.drawPath(path, edgePaint)
        }
    }

    // --- 13. Curl Noise (Organic Fluid Swirl) ---
    private fun renderCurlNoise(canvas: Canvas, w: Int, h: Int, timeMs: Long, speed: Float) {
        val t = (timeMs / 1000f) * speed * 1.5f
        val curlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val streamCount = 14
        for (i in 0 until streamCount) {
            val path = Path()
            val startX = (w.toFloat() / streamCount) * i
            val startY = h * 0.95f
            path.moveTo(startX, startY)
            var curX = startX
            var curY = startY
            val segments = 12
            val stepY = h / segments.toFloat()
            for (s in 0 until segments) {
                val angle = (sin(s * 0.5 + t + i) * 1.8 + cos(curY * 0.005 + t)).toFloat()
                curX += sin(angle) * 32f
                curY -= stepY
                path.lineTo(curX, curY)
            }
            curlPaint.strokeWidth = 6f + sin(i + t).toFloat() * 4f
            curlPaint.shader = LinearGradient(
                startX, startY, curX, curY,
                Color.argb(130, 6, 182, 212),
                Color.argb(30, 168, 85, 247),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, curlPaint)
        }
    }

    // --- 14. 3D Camera Depth of Field ---
    private fun applyDepthOfField(canvas: Canvas, w: Int, h: Int, focusPoint: Float, blurAmount: Float) {
        val focalY = h * focusPoint
        val bandHeight = h * 0.22f
        val dofPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(
                    Color.argb((blurAmount * 4).toInt().coerceIn(20, 120), 20, 25, 40),
                    Color.TRANSPARENT,
                    Color.argb((blurAmount * 4).toInt().coerceIn(20, 120), 20, 25, 40)
                ),
                floatArrayOf(
                    (focalY - bandHeight).coerceAtLeast(0f) / h,
                    focalY / h,
                    (focalY + bandHeight).coerceAtMost(h.toFloat()) / h
                ),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), dofPaint)
    }

    // --- 15. Composition Guides & Grid Overlay ---
    private fun drawCompositionGuides(canvas: Canvas, w: Int, h: Int, type: String) {
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8006B6D4")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        when (type) {
            "RULE_OF_THIRDS" -> {
                val x1 = w / 3f
                val x2 = (w * 2) / 3f
                val y1 = h / 3f
                val y2 = (h * 2) / 3f
                canvas.drawLine(x1, 0f, x1, h.toFloat(), guidePaint)
                canvas.drawLine(x2, 0f, x2, h.toFloat(), guidePaint)
                canvas.drawLine(0f, y1, w.toFloat(), y1, guidePaint)
                canvas.drawLine(0f, y2, w.toFloat(), y2, guidePaint)

                guidePaint.pathEffect = null
                guidePaint.color = Color.parseColor("#E0FBBF24")
                guidePaint.strokeWidth = 3f
                val nodes = listOf(Pair(x1, y1), Pair(x2, y1), Pair(x1, y2), Pair(x2, y2))
                for ((nx, ny) in nodes) {
                    canvas.drawCircle(nx, ny, 6f, guidePaint)
                }
            }
            "SAFE_MARGIN" -> {
                val mAction = RectF(w * 0.05f, h * 0.05f, w * 0.95f, h * 0.95f)
                val mTitle = RectF(w * 0.10f, h * 0.10f, w * 0.90f, h * 0.90f)
                canvas.drawRect(mAction, guidePaint)
                guidePaint.color = Color.parseColor("#80F59E0B")
                canvas.drawRect(mTitle, guidePaint)
            }
            "GRID_4X4" -> {
                for (i in 1..3) {
                    val gx = (w / 4f) * i
                    val gy = (h / 4f) * i
                    canvas.drawLine(gx, 0f, gx, h.toFloat(), guidePaint)
                    canvas.drawLine(0f, gy, w.toFloat(), gy, guidePaint)
                }
            }
        }
    }

    // --- 20. Master Cinematic Finishing: Black Pro-Mist Highlight Bloom ---
    private fun renderCinematicProMist(
        canvas: Canvas,
        output: Bitmap,
        w: Int,
        h: Int,
        intensity: Float,
        warmth: Float
    ) {
        if (w <= 0 || h <= 0) return
        val dsW = (w / 4).coerceAtLeast(32)
        val dsH = (h / 4).coerceAtLeast(32)

        try {
            // Downsample output snapshot for hardware bilinear bloom diffusion
            val smallBmp = Bitmap.createScaledBitmap(output, dsW, dsH, true)

            // Warm golden-amber highlight bloom tint
            val alphaVal = (intensity * 170f).toInt().coerceIn(15, 210)
            val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                alpha = alphaVal
                val g = (245 - warmth * 35).toInt().coerceIn(180, 255)
                val b = (225 - warmth * 75).toInt().coerceIn(140, 255)
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                    setScale(1.0f, g / 255f, b / 255f, 1.0f)
                })
            }

            // Draw primary soft bloom pass
            val destRect = Rect(0, 0, w, h)
            canvas.drawBitmap(smallBmp, null, destRect, bloomPaint)

            // Secondary subtle halation pass slightly expanded
            bloomPaint.alpha = (alphaVal * 0.40f).toInt()
            val destRectOffset = Rect(-6, -6, w + 6, h + 6)
            canvas.drawBitmap(smallBmp, null, destRectOffset, bloomPaint)

            smallBmp.recycle()
        } catch (_: Exception) {
            // Safe fallback
        }
    }

    // --- 21. Master Cinematic Finishing: Organic Celluloid Film Micro-Texture ---
    private fun renderCinematicFilmTexture(
        canvas: Canvas,
        w: Int,
        h: Int,
        timeMs: Long,
        amount: Float
    ) {
        if (w <= 0 || h <= 0) return
        val rand = Random(timeMs / 33L)
        val step = 12
        val grainPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        }

        val baseAlpha = (amount * 70f).toInt().coerceIn(8, 100)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val rndVal = rand.nextInt(100)
                if (rndVal > 40) {
                    val bright = if (rndVal > 72) 255 else 185
                    grainPaint.color = Color.argb(baseAlpha, bright, bright, bright)
                    val px = (x + rand.nextInt(step)).toFloat()
                    val py = (y + rand.nextInt(step)).toFloat()
                    if (rand.nextBoolean()) {
                        canvas.drawRect(px, py, px + 2.0f, py + 2.0f, grainPaint)
                    } else {
                        canvas.drawPoint(px, py, grainPaint)
                    }
                }
                x += step
            }
            y += step
        }
    }

    // --- 22. Master Cinematic Finishing: Soft Optical Lens Vignette ---
    private fun renderCinematicVignette(canvas: Canvas, w: Int, h: Int, amount: Float) {
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val radius = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb((amount * 110).toInt(), 12, 12, 18),
                    Color.argb((amount * 230).toInt(), 0, 0, 0)
                ),
                floatArrayOf(0f, 0.45f, 0.75f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignettePaint)
    }

    // --- 23. Master Cinematic Finishing: 2.39:1 Cinemascope Anamorphic Letterbox ---
    private fun renderCinematicLetterbox(canvas: Canvas, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val targetHeight = w / 2.39f
        val matteHeight = (h - targetHeight) / 2f
        if (matteHeight > 2f) {
            val mattePaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, w.toFloat(), matteHeight, mattePaint)
            canvas.drawRect(0f, h - matteHeight, w.toFloat(), h.toFloat(), mattePaint)
        }
    }

    // --- 24. Flag & Cloth Fluttering Simulation (Kibaran Bendera Tertiup Angin) ---
    private fun renderFlagClothWave(
        canvas: Canvas,
        src: Bitmap,
        w: Int,
        h: Int,
        timeMs: Long,
        config: AeConfig,
        paint: Paint
    ) {
        if (w <= 0 || h <= 0) return
        val meshW = 28
        val meshH = 28
        val totalVerts = (meshW + 1) * (meshH + 1)
        val verts = FloatArray(totalVerts * 2)
        val colors = if (config.flagWave3DShading) IntArray(totalVerts) else null

        val tSec = (timeMs / 1000.0f) * config.flagWaveSpeed
        val amp = config.flagWaveAmplitude
        val freq = config.flagWaveWavelength * 2.0f * Math.PI.toFloat()
        val turb = config.flagWaveTurbulence

        var index = 0
        for (row in 0..meshH) {
            val v = row.toFloat() / meshH
            val origY = v * h

            for (col in 0..meshW) {
                val u = col.toFloat() / meshW
                val origX = u * w

                // Anchor dampening factor (0 at pole/edge, 1 at free flapping edge)
                val anchorFactor: Float = when (config.flagWaveAnchorMode) {
                    "LEFT_POLE" -> Math.pow(u.toDouble(), 0.85).toFloat()
                    "RIGHT_POLE" -> Math.pow((1f - u).toDouble(), 0.85).toFloat()
                    "TOP_BAR" -> Math.pow(v.toDouble(), 0.85).toFloat()
                    "BOTTOM_BAR" -> Math.pow((1f - v).toDouble(), 0.85).toFloat()
                    "DUAL_POLE" -> (4f * u * (1f - u)).coerceIn(0f, 1f)
                    "FREE_WAVE" -> 1.0f
                    else -> Math.pow(u.toDouble(), 0.85).toFloat()
                }

                val (waveCoord, crossCoord) = when (config.flagWaveDirection) {
                    "LEFT_TO_RIGHT" -> Pair(u, v)
                    "RIGHT_TO_LEFT" -> Pair(1f - u, v)
                    "TOP_TO_BOTTOM" -> Pair(v, u)
                    "BOTTOM_TO_TOP" -> Pair(1f - v, u)
                    "DIAGONAL" -> Pair(0.707f * (u + v), 0.707f * (u - v + 1f))
                    "FREE_WAVE" -> Pair(u, v)
                    else -> Pair(u, v)
                }

                // Wave phase equations (primary flutter + secondary harmonic ripple)
                val phase1 = (waveCoord * freq) - (tSec * 4.2f)
                val phase2 = (crossCoord * freq * 1.35f) - (tSec * 2.8f)

                // Wind gust turbulence (soft natural surging)
                val gust = 1.0f + turb * sin(tSec * 2.2f + waveCoord * 1.5f)

                val sin1 = sin(phase1)
                val cos1 = cos(phase1)
                val sin2 = sin(phase2)

                // Transverse wave displacement (ketinggian ombak kain)
                val waveDisp = (sin1 + 0.28f * sin2) * amp * anchorFactor * gust

                // 3D cloth perspective foreshortening (creasing/folding depth)
                val foreshorten = -abs(sin1) * (amp * 0.28f) * anchorFactor

                var dx = origX
                var dy = origY

                when (config.flagWaveDirection) {
                    "LEFT_TO_RIGHT" -> {
                        dy += waveDisp
                        dx += foreshorten
                    }
                    "RIGHT_TO_LEFT" -> {
                        dy += waveDisp
                        dx -= foreshorten
                    }
                    "TOP_TO_BOTTOM" -> {
                        dx += waveDisp
                        dy += foreshorten
                    }
                    "BOTTOM_TO_TOP" -> {
                        dx += waveDisp
                        dy -= foreshorten
                    }
                    "DIAGONAL" -> {
                        dx += 0.707f * (waveDisp + foreshorten)
                        dy += 0.707f * (waveDisp - foreshorten)
                    }
                    else -> {
                        dy += waveDisp
                        dx += foreshorten
                    }
                }

                verts[index * 2] = dx
                verts[index * 2 + 1] = dy

                if (colors != null) {
                    val slope = cos1 * anchorFactor
                    val shade = (1.0f + 0.28f * slope).coerceIn(0.65f, 1.18f)
                    val r = (255 * shade).toInt().coerceIn(145, 255)
                    val g = (255 * shade).toInt().coerceIn(145, 255)
                    val b = (255 * shade).toInt().coerceIn(145, 255)
                    colors[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }

                index++
            }
        }

        canvas.drawBitmapMesh(src, meshW, meshH, verts, 0, colors, 0, paint)
    }

    private fun renderMediaOverlayFlagWave(
        canvas: Canvas,
        src: Bitmap,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        timeMs: Long,
        overlay: AeMediaOverlay,
        paint: Paint
    ) {
        if (w <= 0f || h <= 0f) return
        val meshW = 20
        val meshH = 20
        val totalVerts = (meshW + 1) * (meshH + 1)
        val verts = FloatArray(totalVerts * 2)
        val colors = IntArray(totalVerts)

        val tSec = (timeMs / 1000.0f) * overlay.flagWaveSpeed
        val amp = overlay.flagWaveAmplitude
        val freq = 2.0f * Math.PI.toFloat()

        var index = 0
        for (row in 0..meshH) {
            val v = row.toFloat() / meshH
            val origY = top + v * h

            for (col in 0..meshW) {
                val u = col.toFloat() / meshW
                val origX = left + u * w

                val anchorFactor: Float = when (overlay.flagWaveAnchorMode) {
                    "LEFT_POLE" -> Math.pow(u.toDouble(), 0.85).toFloat()
                    "RIGHT_POLE" -> Math.pow((1f - u).toDouble(), 0.85).toFloat()
                    "FREE_WAVE" -> 1.0f
                    else -> Math.pow(u.toDouble(), 0.85).toFloat()
                }

                val waveCoord = if (overlay.flagWaveDirection == "RIGHT_TO_LEFT") 1f - u else u
                val phase1 = (waveCoord * freq) - (tSec * 4.2f)
                val sin1 = sin(phase1)
                val cos1 = cos(phase1)

                val waveDisp = sin1 * amp * anchorFactor
                val foreshorten = -abs(sin1) * (amp * 0.25f) * anchorFactor

                verts[index * 2] = origX + foreshorten
                verts[index * 2 + 1] = origY + waveDisp

                val slope = cos1 * anchorFactor
                val shade = (1.0f + 0.25f * slope).coerceIn(0.68f, 1.15f)
                val r = (255 * shade).toInt().coerceIn(155, 255)
                val g = (255 * shade).toInt().coerceIn(155, 255)
                val b = (255 * shade).toInt().coerceIn(155, 255)
                colors[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                index++
            }
        }

        canvas.drawBitmapMesh(src, meshW, meshH, verts, 0, colors, 0, paint)
     }

    // --- BG Custom Helpers ---
    private fun renderBackgroundGradient(canvas: Canvas, w: Int, h: Int, preset: String) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (preset) {
            "SUNSET" -> {
                p.shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    intArrayOf(Color.parseColor("#FF512F"), Color.parseColor("#F09819"), Color.parseColor("#DD2476")),
                    null, Shader.TileMode.CLAMP
                )
            }
            "CYBERPUNK" -> {
                p.shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(Color.parseColor("#7928CA"), Color.parseColor("#4F46E5"), Color.parseColor("#00DFD8")),
                    null, Shader.TileMode.CLAMP
                )
            }
            "EMERALD_GOLD" -> {
                p.shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(Color.parseColor("#065F46"), Color.parseColor("#059669"), Color.parseColor("#F59E0B")),
                    null, Shader.TileMode.CLAMP
                )
            }
            "DEEP_SPACE" -> {
                val r = max(w, h) * 0.8f
                p.shader = RadialGradient(
                    w * 0.5f, h * 0.4f, r,
                    intArrayOf(Color.parseColor("#312E81"), Color.parseColor("#0F172A"), Color.parseColor("#030712")),
                    null, Shader.TileMode.CLAMP
                )
            }
            "PASTEL" -> {
                p.shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(Color.parseColor("#A8EDEA"), Color.parseColor("#FED6E3")),
                    null, Shader.TileMode.CLAMP
                )
            }
            else -> { // STUDIO_DARK
                val r = max(w, h) * 0.75f
                p.shader = RadialGradient(
                    w * 0.5f, h * 0.45f, r,
                    intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#0B132B"), Color.BLACK),
                    null, Shader.TileMode.CLAMP
                )
            }
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun renderBlurredMediaBackground(canvas: Canvas, src: Bitmap, w: Int, h: Int, blurRadius: Float) {
        val downW = max(32, src.width / 16)
        val downH = max(32, src.height / 16)
        val small = Bitmap.createScaledBitmap(src, downW, downH, true)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val srcR = Rect(0, 0, downW, downH)
        val dstR = Rect(0, 0, w, h)
        canvas.drawBitmap(small, srcR, dstR, p)
        small.recycle()

        // Subtle dark cinematic tint so foreground media pops out
        val dimPaint = Paint().apply {
            color = Color.parseColor("#59000000") // 35% black overlay
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), dimPaint)
    }

    private fun calculateBaseMediaDstRect(
        srcW: Int,
        srcH: Int,
        compW: Int,
        compH: Int,
        fitMode: String,
        scale: Float
    ): RectF {
        if (srcW <= 0 || srcH <= 0 || compW <= 0 || compH <= 0) {
            return RectF(0f, 0f, compW.toFloat(), compH.toFloat())
        }

        val dw: Float
        val dh: Float
        when (fitMode) {
            "FILL" -> {
                val s = max(compW.toFloat() / srcW, compH.toFloat() / srcH) * scale
                dw = srcW * s
                dh = srcH * s
            }
            "STRETCH" -> {
                dw = compW.toFloat() * scale
                dh = compH.toFloat() * scale
            }
            "CUSTOM" -> {
                dw = srcW.toFloat() * scale
                dh = srcH.toFloat() * scale
            }
            else -> { // "FIT"
                val s = min(compW.toFloat() / srcW, compH.toFloat() / srcH) * scale
                dw = srcW * s
                dh = srcH * s
            }
        }
        val left = (compW - dw) / 2f
        val top = (compH - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }
}