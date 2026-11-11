package com.udintpp.mediastudio.engine.vfx

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
            renderParticleWorld(canvas, w, h, timeMs, config.activeParticleType!!, config.particleCount, config.particleSpeed * config.effectSpeed)
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

    // --- 6. Particle World Simulation ---
    private fun renderParticleWorld(
        canvas: Canvas,
        w: Int,
        h: Int,
        timeMs: Long,
        type: AeEffectType,
        count: Int,
        speed: Float
    ) {
        val pPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (type) {
            AeEffectType.PARTICLE_SPARKS -> {
                pPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                for (i in 0 until count) {
                    val seed = i * 1337L
                    val life = ((timeMs * speed + seed) % 2000L) / 2000f
                    val x = ((seed * 17) % w) + sin((life * 6f).toDouble()).toFloat() * 40f
                    val y = h - (life * h * 1.1f)
                    val size = (1f - life) * 8f
                    val alpha = ((1f - life) * 255).toInt().coerceIn(0, 255)

                    pPaint.color = if (i % 2 == 0) Color.parseColor("#F59E0B") else Color.parseColor("#EF4444")
                    pPaint.alpha = alpha
                    canvas.drawCircle(x, y, size, pPaint)
                }
            }
            AeEffectType.PARTICLE_RAIN -> {
                pPaint.color = Color.parseColor("#70E2E8F0")
                pPaint.strokeWidth = 2.5f
                for (i in 0 until count * 2) {
                    val seed = i * 997L
                    val fallY = ((timeMs * speed * 2f + seed) % h)
                    val x = (seed * 31) % w
                    canvas.drawLine(x.toFloat(), fallY, x - 10f, fallY + 30f, pPaint)
                }
            }
            AeEffectType.PARTICLE_SNOW -> {
                pPaint.color = Color.WHITE
                for (i in 0 until count) {
                    val seed = i * 613L
                    val driftY = ((timeMs * speed * 0.3f + seed) % h)
                    val driftX = ((seed * 43) % w) + sin((driftY / 40f).toDouble()).toFloat() * 20f
                    val radius = 3f + (seed % 4)
                    val alpha = 120 + (seed % 120).toInt()
                    pPaint.alpha = alpha
                    canvas.drawCircle(driftX, driftY, radius, pPaint)
                }
            }
            AeEffectType.PARTICLE_BOKEH -> {
                pPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                for (i in 0 until (count / 2).coerceAtLeast(8)) {
                    val seed = i * 2039L
                    val floatY = ((timeMs * speed * 0.15f + seed) % h)
                    val floatX = ((seed * 57) % w)
                    val radius = 25f + (seed % 45)
                    val alpha = 35 + (seed % 40).toInt()

                    val bokehColors = intArrayOf(
                        Color.parseColor("#8B5CF6"),
                        Color.parseColor("#06B6D4"),
                        Color.parseColor("#EC4899"),
                        Color.parseColor("#F59E0B")
                    )
                    pPaint.color = bokehColors[i % bokehColors.size]
                    pPaint.alpha = alpha
                    canvas.drawCircle(floatX.toFloat(), floatY, radius, pPaint)
                }
            }
            else -> {}
        }
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
                drawEmbossedText(canvas, px, py, substring, textPaint, item)
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
                drawEmbossedText(canvas, 0f, 0f, item.text, textPaint, item)
                canvas.restore()
            }
            "NEON_PULSE" -> {
                val pulse = 0.7f + 0.3f * sin((speedMs / 180f).toDouble()).toFloat()
                textPaint.alpha = (pulse * 255).toInt().coerceIn(80, 255)
                drawEmbossedText(canvas, px, py, item.text, textPaint, item)
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
                drawEmbossedText(canvas, 0f, 0f, item.text, textPaint, item)
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
                drawEmbossedText(canvas, 0f, 0f, item.text, textPaint, item)
                canvas.restore()
            }
            "WAVE_BOUNCE" -> {
                val bounce = sin((speedMs / 130f).toDouble()).toFloat() * 16f
                canvas.save()
                canvas.translate(px, py + bounce)
                drawEmbossedText(canvas, 0f, 0f, item.text, textPaint, item)
                canvas.restore()
            }
            "FADE_SLIDE" -> {
                val cycleMs = 2500L
                val t = ((speedMs % cycleMs).toFloat() / cycleMs).coerceIn(0f, 1f)
                val slideY = if (t < 0.25f) (1f - (t / 0.25f)) * 35f else 0f
                textPaint.alpha = ((t.coerceIn(0f, 0.25f) / 0.25f) * 255).toInt()
                canvas.save()
                canvas.translate(px, py + slideY)
                drawEmbossedText(canvas, 0f, 0f, item.text, textPaint, item)
                canvas.restore()
            }
            else -> {
                drawEmbossedText(canvas, px, py, item.text, textPaint, item)
            }
        }
    }

    private fun drawEmbossedText(
        canvas: Canvas,
        x: Float,
        y: Float,
        text: String,
        textPaint: Paint,
        item: AeMotionText
    ) {
        // 1. Efek Huruf Timbul 3D (3D Extrusion Depth / Bevel)
        if (item.isEmbossEnabled) {
            val depth = item.embossDepth.coerceIn(1f, 15f)
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
}
