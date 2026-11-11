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
import com.udintpp.mediastudio.data.model.AeMotionText
import com.udintpp.mediastudio.data.model.AeSticker
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object AfterEffectsRenderer {

    private val random = Random()

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
        val w = src.width
        val h = src.height

        val output = if (reusableOutput != null && !reusableOutput.isRecycled && reusableOutput.width == w && reusableOutput.height == h) {
            reusableOutput.eraseColor(Color.TRANSPARENT)
            reusableOutput
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 1. Evaluate Keyframing (Position, Scale, Rotation, 3D RotX/Y, Opacity)
        val transform = evaluateKeyframeTransform(config.keyframes, timeMs)

        // 2. Evaluate Camera Wiggle Shake (AE Handheld Wiggle)
        val wiggleOffset = if (config.isWiggleEnabled) {
            evaluateCameraWiggle(timeMs, config.wiggleFrequency, config.wiggleAmplitude)
        } else {
            PointF(0f, 0f)
        }

        // Apply transforms to canvas (including 3D Layer Space)
        canvas.save()
        val centerX = w / 2f
        val centerY = h / 2f
        canvas.translate(centerX + transform.posX + wiggleOffset.x, centerY + transform.posY + wiggleOffset.y)
        canvas.rotate(transform.rotation)
        canvas.scale(transform.scale, transform.scale)

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

        // 3. Render Base Video Frame, Turbulent Displace, or RGB Split Glitch
        if (config.isRgbGlitchEnabled) {
            renderRgbGlitch(canvas, src, timeMs, config.glitchIntensity, config.glitchScanlines, paint)
        } else if (config.isTurbulentDisplaceEnabled) {
            renderTurbulentDisplace(canvas, src, timeMs, config.turbulentAmount, config.turbulentSpeed, paint)
        } else {
            canvas.drawBitmap(src, 0f, 0f, paint)
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
        val count = config.particleCount.coerceIn(10, 300)
        val globalSpeed = config.particleSpeed * config.effectSpeed
        val baseSize = config.particleSize
        val turbulence = config.particleTurbulence.coerceIn(0f, 3.5f)
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

            // Natural individual lifecycle & fading (smooth sinusoidal breath)
            val cycleSec = 3.2f / indivSpeed.coerceAtLeast(0.2f)
            val phase = ((t + hash1 * cycleSec) % cycleSec) / cycleSec
            val fadeInOut = sin(phase * Math.PI).toFloat().coerceIn(0f, 1f)

            // Twinkle pulsation
            val twinkleFreq = config.particleTwinkleRate * (2.0f + hash0 * 3.5f)
            val twinkle = (sin(t * twinkleFreq + hash2 * 6.28f) * 0.35f + 0.65f).toFloat().coerceIn(0.1f, 1f)
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
                else -> {}
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

    // --- 9. Motion Graphics Text Engine with 3D Emboss, Custom Fonts & Animations ---
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
                "SERIF" -> Typeface.SERIF
                "SANS_SERIF" -> Typeface.SANS_SERIF
                "CASUAL" -> Typeface.create("casual", Typeface.BOLD)
                else -> Typeface.DEFAULT_BOLD
            }
        }

        val px = w * item.x
        val py = h * item.y
        val speedMs = (timeMs * effectSpeed).toLong()

        when (item.style) {
            "TYPEWRITER" -> {
                val cycleMs = 3500L
                val t = (speedMs % cycleMs).toFloat() / cycleMs
                val charsToShow = (t * item.text.length * 1.3f).toInt().coerceIn(0, item.text.length)
                val substring = item.text.substring(0, charsToShow)
                drawEmbossedText(canvas, px, py, substring, textPaint, item, timeMs)
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
                drawEmbossedText(canvas, 0f, 0f, item.text, textPaint, item, timeMs)
                canvas.restore()
            }
            "NEON_PULSE" -> {
                val pulse = 0.7f + 0.3f * sin((speedMs / 180f).toDouble()).toFloat()
                textPaint.alpha = (pulse * 255).toInt().coerceIn(80, 255)
                drawEmbossedText(canvas, px, py, item.text, textPaint, item, timeMs)
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
                drawEmbossedText(canvas, 0f, 0f, item.text, textPaint, item, timeMs)
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
                drawEmbossedText(canvas, 0f, 0f, item.text, textPaint, item, timeMs)
                canvas.restore()
            }
            "WAVE_BOUNCE" -> {
                val bounce = sin((speedMs / 130f).toDouble()).toFloat() * 16f
                canvas.save()
                canvas.translate(px, py + bounce)
                drawEmbossedText(canvas, 0f, 0f, item.text, textPaint, item, timeMs)
                canvas.restore()
            }
            "FADE_SLIDE" -> {
                val cycleMs = 2500L
                val t = ((speedMs % cycleMs).toFloat() / cycleMs).coerceIn(0f, 1f)
                val slideY = if (t < 0.25f) (1f - (t / 0.25f)) * 35f else 0f
                textPaint.alpha = ((t.coerceIn(0f, 0.25f) / 0.25f) * 255).toInt()
                canvas.save()
                canvas.translate(px, py + slideY)
                drawEmbossedText(canvas, 0f, 0f, item.text, textPaint, item, timeMs)
                canvas.restore()
            }
            else -> {
                drawEmbossedText(canvas, px, py, item.text, textPaint, item, timeMs)
            }
        }
    }

    private fun drawEmbossedText(
        canvas: Canvas,
        x: Float,
        y: Float,
        text: String,
        textPaint: Paint,
        item: AeMotionText,
        timeMs: Long
    ) {
        if (text.isEmpty()) return

        // 1. Efek Huruf Timbul 3D (3D Extrusion Depth / Bevel)
        if (item.isEmbossEnabled) {
            val depth = item.embossDepth.coerceIn(1f, 20f)
            val shadowPaint = Paint(textPaint).apply {
                color = item.shadowColor
                style = Paint.Style.FILL
                clearShadowLayer()
            }
            for (d in 1..depth.toInt()) {
                canvas.drawText(text, x + d, y + d, shadowPaint)
            }
        }

        // 2. Stroke / Border garis luar teks
        if (item.strokeWidth > 0f) {
            val strokePaint = Paint(textPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = item.strokeWidth
                color = item.strokeColor
                clearShadowLayer()
            }
            canvas.drawText(text, x, y, strokePaint)
        }

        // 3. Main face text dengan outer neon glow
        textPaint.setShadowLayer(item.shadowRadius, 0f, 0f, item.glowColor)
        canvas.drawText(text, x, y, textPaint)

        // 4. Shimmer / Metallic Sheen sweep melintasi teks
        if (item.isShimmerEnabled) {
            val textWidth = textPaint.measureText(text)
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
            canvas.drawText(text, x, y, textPaint)
            canvas.drawText(text, x, y, shimmerPaint)
            canvas.restore()
        }

        // 5. Star Glint Sparkles (Kilau Bintang 4-Sudut di Sudut Huruf)
        if (item.isStarGlintEnabled) {
            val textWidth = textPaint.measureText(text)
            val leftX = x - (textWidth * 0.48f)
            val topY = y - (item.fontSize * 0.75f)

            // Corner 1: Awal kata
            val phase1 = (sin((timeMs * 0.006f) + 0f) * 0.5f + 0.5f)
            val rot1 = (timeMs * 0.08f) % 360f
            drawStarGlint(canvas, leftX + 8f, topY + 4f, 8f * phase1, item.starGlintColor, (phase1 * 255).toInt(), rot1)

            // Corner 2: Akhir kata
            val phase2 = (sin((timeMs * 0.006f) + 2.2f) * 0.5f + 0.5f)
            val rot2 = (timeMs * -0.06f + 45f) % 360f
            drawStarGlint(canvas, leftX + textWidth - 8f, topY + 2f, 7.5f * phase2, item.starGlintColor, (phase2 * 255).toInt(), rot2)

            // Corner 3: Tengah atas
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
}
