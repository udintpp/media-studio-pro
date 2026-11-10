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
    LIGHT_RAYS(
        id = "light_rays",
        displayName = "☀️ CC Light Rays (God Rays)",
        category = "Generate",
        description = "Pancaran sinar cahaya volumetrik berkilau (god rays) memancar dramatis dari pusat pencahayaan."
    ),
    TURBULENT_DISPLACE(
        id = "turbulent_displace",
        displayName = "🌊 Turbulent Displace (Liquify)",
        category = "Distort",
        description = "Distorsi riak gelombang cairan, efek panas atmosferik, dan asap organik dinamis khas After Effects."
    ),
    ZOOM_BLUR(
        id = "zoom_blur",
        displayName = "🌀 CC Radial Fast Blur (Zoom)",
        category = "Blur & Sharpen",
        description = "Efek zoom burst radial eksplosif ke arah pusat untuk transisi aksi berkecepatan tinggi."
    ),
    SABER_NEON_EDGE(
        id = "saber_neon",
        displayName = "⚡ Saber Laser & Neon Edge",
        category = "Stylize",
        description = "Efek kontur garis tepi laser berenergi tinggi bercahaya neon ala plugin Video Copilot Saber."
    ),
    ECHO_TRAIL(
        id = "echo_trail",
        displayName = "👻 Echo & Motion Trails",
        category = "Time",
        description = "Bayangan jejak waktu berulang (echo trails) di belakang objek bergerak menciptakan efek surealis."
    ),
    LUMETRI_COLOR(
        id = "lumetri_color",
        displayName = "🎨 Lumetri Color Curves",
        category = "Color Correction",
        description = "Grading warna sinematik Hollywood: Teal & Orange, Cyberpunk Neo, Noir Klasik, dan Matrix Emerald."
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

enum class AeMaskShape {
    RECTANGLE, ELLIPSE
}

enum class AeMaskMode {
    ADD, SUBTRACT, INTERSECT
}

data class AeMask(
    var isEnabled: Boolean = false,
    var shape: AeMaskShape = AeMaskShape.RECTANGLE,
    var mode: AeMaskMode = AeMaskMode.ADD,
    var left: Float = 0.15f,
    var top: Float = 0.15f,
    var right: Float = 0.85f,
    var bottom: Float = 0.85f,
    var feather: Float = 20f,
    var isInverted: Boolean = false,
    var opacity: Float = 1.0f
)

data class AeKeyframe(
    val timeMs: Long,
    val posX: Float = 0f,
    val posY: Float = 0f,
    val scale: Float = 1.0f,
    val rotation: Float = 0f,
    val rotX: Float = 0f,
    val rotY: Float = 0f,
    val opacity: Float = 1.0f,
    val easeType: String = "EASY_EASE" // "LINEAR", "EASE_IN", "EASE_OUT", "EASY_EASE", "BOUNCE", "ELASTIC"
)

data class AeMotionText(
    val id: String = java.util.UUID.randomUUID().toString(),
    var text: String = "AFTER EFFECTS TITLE",
    var style: String = "TYPEWRITER", // "TYPEWRITER", "KINETIC_ZOOM", "NEON_PULSE", "GLITCH", "FADE_SLIDE", "3D_FLIP", "WAVE_BOUNCE"
    var color: Int = Color.WHITE,
    var glowColor: Int = Color.parseColor("#06B6D4"),
    var fontSize: Float = 44f,
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var fontFamily: String = "DEFAULT_BOLD", // "DEFAULT_BOLD", "MONOSPACE", "SERIF", "SANS_SERIF", "CASUAL"
    var isEmbossEnabled: Boolean = true, // Efek huruf timbul 3D
    var embossDepth: Float = 4f,
    var strokeColor: Int = Color.BLACK,
    var strokeWidth: Float = 2.5f,
    var shadowColor: Int = Color.BLACK,
    var shadowRadius: Float = 10f,
    var opacity: Float = 1.0f
)

data class AeSticker(
    val id: String = java.util.UUID.randomUUID().toString(),
    var content: String = "🔥",
    var x: Float = 0.5f,
    var y: Float = 0.35f,
    var scale: Float = 1.0f,
    var rotation: Float = 0f,
    var opacity: Float = 1.0f,
    var animationType: String = "PULSE" // "NONE", "PULSE", "BOUNCE", "SPIN", "WOBBLE", "FLOAT"
)

data class AeConfig(
    // Composition Settings (Custom Durasi, Resolusi & Rasio)
    var compDurationMs: Long = 5000L,
    var compWidth: Int = 1920,
    var compHeight: Int = 1080,
    var compAspectRatio: String = "16:9", // "16:9", "9:16", "1:1", "4:5", "21:9", "CUSTOM"

    // 3D Layer Switch (After Effects 3D Space)
    var is3DLayerEnabled: Boolean = false,
    var rotateX: Float = 0f, // Pitch / Tilt 3D
    var rotateY: Float = 0f, // Yaw / Pan 3D
    var cameraDistanceZ: Float = -10f,

    // Masking & Rotoscoping (Pen / Shape Masks)
    var mask: AeMask = AeMask(),

    // Speed Ramping & Time Remapping
    var speedMultiplier: Float = 1.0f,
    var isTimeReversed: Boolean = false,

    // Global Effect Speed
    var effectSpeed: Float = 1.0f, // 0.2x to 3.0x

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

    var isLightRaysEnabled: Boolean = false,
    var lightRaysIntensity: Float = 0.75f,
    var lightRaysLength: Float = 60f,
    var lightRaysX: Float = 0.5f,
    var lightRaysY: Float = 0.3f,

    var isTurbulentDisplaceEnabled: Boolean = false,
    var turbulentAmount: Float = 20f,
    var turbulentSpeed: Float = 1.0f,

    var isZoomBlurEnabled: Boolean = false,
    var zoomBlurAmount: Float = 25f,

    var isSaberEdgeEnabled: Boolean = false,
    var saberGlowColor: Int = Color.parseColor("#06B6D4"),
    var saberWidth: Float = 8f,

    var isEchoTrailEnabled: Boolean = false,
    var echoCount: Int = 3,

    var lumetriPreset: String = "NONE", // "NONE", "TEAL_ORANGE", "CYBERPUNK", "FILM_NOIR", "MATRIX", "VINTAGE_WARM"

    // Particle Controls
    var activeParticleType: AeEffectType? = null,
    var particleCount: Int = 50,
    var particleSpeed: Float = 1.0f,
    var particleSize: Float = 1.0f,

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
    val motionTexts: MutableList<AeMotionText> = mutableListOf(),

    // Stickers
    val stickers: MutableList<AeSticker> = mutableListOf()
)
