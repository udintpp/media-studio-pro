package com.udintpp.mediastudio.data.model

import android.graphics.Color
import android.graphics.PorterDuff

enum class AeEffectType(
    val id: String,
    val displayName: String,
    val category: String,
    val description: String
) {
    OPTICAL_GLOW(
        id = "optical_glow",
        displayName = "🌟 Optical Deep Glow",
        category = "Stylize",
        description = "Radiance glow dengan pencahayaan neon yang halus, ambang batas dinamis, dan radiasi warna intensif."
    ),
    RGB_SPLIT_GLITCH(
        id = "rgb_glitch",
        displayName = "⚡ RGB Split & Glitch",
        category = "Distort",
        description = "Efek aberasi kromatik kanal Red/Cyan terpisah dengan scanlines horizontal bergaya cyberpunk."
    ),
    CAMERA_WIGGLE(
        id = "camera_wiggle",
        displayName = "📳 Camera Wiggle (AE Shake)",
        category = "Transform",
        description = "Simulasi getaran kamera genggam natural organik (handheld wiggle) dengan frekuensi dan amplitudo."
    ),
    LENS_FLARE(
        id = "lens_flare",
        displayName = "☀️ Anamorphic Lens Flare",
        category = "Generate",
        description = "Kilatan lensa sinematik Hollywood dengan garis streak horizontal cyan/emas dan cincin refleksi optik."
    ),
    PARTICLE_SPARKS(
        id = "particle_sparks",
        displayName = "✨ Particle World: Sparks & Embers",
        category = "Simulation",
        description = "Simulasi partikel fisik percikan api dan bara melayang dengan gravitasi dan hamburan dinamis."
    ),
    PARTICLE_RAIN(
        id = "particle_rain",
        displayName = "🌧️ Particle World: Rain & Mist",
        category = "Simulation",
        description = "Simulasi cuaca tetesan air hujan sinematik dengan kabut atmosferik dan kecepatan jatuh realistis."
    ),
    PARTICLE_SNOW(
        id = "particle_snow",
        displayName = "❄️ Particle World: Snow Drift",
        category = "Simulation",
        description = "Simulasi butiran salju lembut yang melayang perlahan dengan gerakan turbulensi angin."
    ),
    PARTICLE_BOKEH(
        id = "particle_bokeh",
        displayName = "🔮 Particle World: Bokeh Orbs",
        category = "Simulation",
        description = "Lingkaran bias cahaya bokeh fotografi aperture lebar dengan kedalaman ruang optik."
    ),
    FILM_GRAIN(
        id = "film_grain",
        displayName = "🎞️ 35mm Celluloid Film Grain",
        category = "Stylize",
        description = "Tekstur butiran film seluloid analog 35mm dengan getaran eksposur halus khas bioskop klasik."
    ),
    MOTION_BLUR(
        id = "motion_blur",
        displayName = "💨 Directional Motion Blur",
        category = "Blur & Sharpen",
        description = "Efek blur kecepatan terarah untuk menciptakan kesan pergerakan cepat dan aksi dramatis."
    ),
    AUDIO_SPECTRUM(
        id = "audio_spectrum",
        displayName = "🎵 Audio Waveform Visualizer",
        category = "Audio Reactive",
        description = "Visualisasi gelombang frekuensi suara neon yang bereaksi terhadap dentuman ritme musik."
    )
}

enum class AeBlendMode(
    val displayName: String,
    val porterDuffMode: PorterDuff.Mode
) {
    NORMAL("Normal", PorterDuff.Mode.SRC_OVER),
    ADD("Add (Linear Dodge)", PorterDuff.Mode.ADD),
    SCREEN("Screen", PorterDuff.Mode.SCREEN),
    MULTIPLY("Multiply", PorterDuff.Mode.MULTIPLY),
    OVERLAY("Overlay", PorterDuff.Mode.OVERLAY),
    LIGHTEN("Lighten", PorterDuff.Mode.LIGHTEN),
    DARKEN("Darken", PorterDuff.Mode.DARKEN),
    XOR("Difference / XOR", PorterDuff.Mode.XOR)
}

data class AeKeyframe(
    val timeMs: Long,
    val posX: Float = 0f,
    val posY: Float = 0f,
    val scale: Float = 1.0f,
    val rotation: Float = 0f,
    val opacity: Float = 1.0f,
    val easeType: String = "EASY_EASE" // "LINEAR", "EASE_IN", "EASE_OUT", "EASY_EASE"
)

data class AeMotionText(
    var text: String = "AFTER EFFECTS TITLE",
    var style: String = "TYPEWRITER", // "TYPEWRITER", "KINETIC_ZOOM", "NEON_PULSE", "GLITCH", "FADE_SLIDE"
    var color: Int = Color.WHITE,
    var glowColor: Int = Color.parseColor("#06B6D4"),
    var fontSize: Float = 36f,
    var x: Float = 0.5f,
    var y: Float = 0.5f
)

data class AeConfig(
    // Effect toggles
    var isOpticalGlowEnabled: Boolean = false,
    var glowIntensity: Float = 0.75f,
    var glowRadius: Float = 25f,
    var glowColor: Int = Color.parseColor("#8B5CF6"),

    var isRgbGlitchEnabled: Boolean = false,
    var glitchIntensity: Float = 0.5f,
    var glitchScanlines: Boolean = true,

    var isWiggleEnabled: Boolean = false,
    var wiggleFrequency: Float = 4.0f, // Hz
    var wiggleAmplitude: Float = 20.0f, // pixels

    var isLensFlareEnabled: Boolean = false,
    var flareIntensity: Float = 0.7f,
    var flareX: Float = 0.35f,
    var flareY: Float = 0.35f,

    var activeParticleType: AeEffectType? = null,
    var particleCount: Int = 40,
    var particleSpeed: Float = 1.0f,

    var isFilmGrainEnabled: Boolean = false,
    var grainAmount: Float = 0.25f,

    var isMotionBlurEnabled: Boolean = false,
    var blurAngle: Float = 45f,
    var blurDistance: Float = 15f,

    var isAudioSpectrumEnabled: Boolean = false,

    // Layer Blending
    var blendMode: AeBlendMode = AeBlendMode.NORMAL,

    // Keyframing
    val keyframes: MutableList<AeKeyframe> = mutableListOf(),

    // Motion Text
    val motionTexts: MutableList<AeMotionText> = mutableListOf()
)
