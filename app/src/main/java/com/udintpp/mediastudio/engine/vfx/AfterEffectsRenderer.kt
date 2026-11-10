package com.udintpp.mediastudio.engine.vfx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.udintpp.mediastudio.data.model.AeConfig
import com.udintpp.mediastudio.data.model.AeEffectType
import com.udintpp.mediastudio.data.model.AeKeyframe
import com.udintpp.mediastudio.data.model.AeMotionText
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
     * particle simulations, optical glow, and motion graphics onto a frame bitmap.
     */
    fun renderFrame(
        src: Bitmap,
        timeMs: Long,
        config: AeConfig
    ): Bitmap {
        val w = src.width
        val h = src.height

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 1. Evaluate Keyframing (Position, Scale, Rotation, Opacity)
        val transform = evaluateKeyframeTransform(config.keyframes, timeMs)

        // 2. Evaluate Camera Wiggle Shake (AE Handheld Wiggle)
        val wiggleOffset = if (config.isWiggleEnabled) {
            evaluateCameraWiggle(timeMs, config.wiggleFrequency, config.wiggleAmplitude)
        } else {
            PointF(0f, 0f)
        }

        // Apply transforms to canvas
        canvas.save()
        val centerX = w / 2f
        val centerY = h / 2f
        canvas.translate(centerX + transform.posX + wiggleOffset.x, centerY + transform.posY + wiggleOffset.y)
        canvas.rotate(transform.rotation)
        canvas.scale(transform.scale, transform.scale)
        canvas.translate(-centerX, -centerY)

        paint.alpha = (transform.opacity * 255).toInt().coerceIn(0, 255)
        paint.xfermode = PorterDuffXfermode(config.blendMode.porterDuffMode)

        // 3. Render Base Video Frame or RGB Split Glitch
        if (config.isRgbGlitchEnabled) {
            renderRgbGlitch(canvas, src, timeMs, config.glitchIntensity, config.glitchScanlines, paint)
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

        // 6. Particle World Simulation
        if (config.activeParticleType != null) {
            renderParticleWorld(canvas, w, h, timeMs, config.activeParticleType!!, config.particleCount, config.particleSpeed)
        }

        // 7. 35mm Celluloid Film Grain
        if (config.isFilmGrainEnabled) {
            renderFilmGrain(canvas, w, h, timeMs, config.grainAmount)
        }

        // 8. Audio Waveform Spectrum Visualizer
        if (config.isAudioSpectrumEnabled) {
            renderAudioWaveform(canvas, w, h, timeMs)
        }

        // 9. Motion Graphics Text
        for (motionText in config.motionTexts) {
            renderMotionText(canvas, w, h, timeMs, motionText)
        }

        return output
    }

    // --- 1. Keyframing Engine ---
    private data class InterpolatedTransform(
        val posX: Float = 0f,
        val posY: Float = 0f,
        val scale: Float = 1.0f,
        val rotation: Float = 0f,
        val opacity: Float = 1.0f
    )

    private fun evaluateKeyframeTransform(keyframes: List<AeKeyframe>, timeMs: Long): InterpolatedTransform {
        if (keyframes.isEmpty()) return InterpolatedTransform()
        if (keyframes.size == 1) {
            val k = keyframes[0]
            return InterpolatedTransform(k.posX, k.posY, k.scale, k.rotation, k.opacity)
        }

        val sorted = keyframes.sortedBy { it.timeMs }
        if (timeMs <= sorted.first().timeMs) {
            val k = sorted.first()
            return InterpolatedTransform(k.posX, k.posY, k.scale, k.rotation, k.opacity)
        }
        if (timeMs >= sorted.last().timeMs) {
            val k = sorted.last()
            return InterpolatedTransform(k.posX, k.posY, k.scale, k.rotation, k.opacity)
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
            else -> t // Linear
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

    // --- 9. Motion Graphics Text Engine ---
    private fun renderMotionText(canvas: Canvas, w: Int, h: Int, timeMs: Long, item: AeMotionText) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.color
            textSize = item.fontSize
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(16f, 0f, 0f, item.glowColor)
        }

        val px = w * item.x
        val py = h * item.y

        when (item.style) {
            "TYPEWRITER" -> {
                val cycleMs = 3500L
                val t = (timeMs % cycleMs).toFloat() / cycleMs
                val charsToShow = (t * item.text.length * 1.3f).toInt().coerceIn(0, item.text.length)
                val substring = item.text.substring(0, charsToShow)
                canvas.drawText(substring, px, py, textPaint)
            }
            "KINETIC_ZOOM" -> {
                val cycleMs = 1500L
                val t = (timeMs % cycleMs).toFloat() / cycleMs
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
                canvas.drawText(item.text, 0f, 0f, textPaint)
                canvas.restore()
            }
            "NEON_PULSE" -> {
                val pulse = 0.7f + 0.3f * sin((timeMs / 180f).toDouble()).toFloat()
                textPaint.alpha = (pulse * 255).toInt().coerceIn(80, 255)
                textPaint.setShadowLayer(25f * pulse, 0f, 0f, item.glowColor)
                canvas.drawText(item.text, px, py, textPaint)
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
                canvas.drawText(item.text, 0f, 0f, textPaint)
                canvas.restore()
            }
            else -> {
                canvas.drawText(item.text, px, py, textPaint)
            }
        }
    }
}
