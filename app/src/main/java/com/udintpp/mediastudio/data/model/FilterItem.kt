package com.udintpp.mediastudio.data.model

data class ImageAdjustments(
    var brightness: Float = 0f,     // -100f to +100f (0 default)
    var contrast: Float = 1.0f,     // 0.2f to 2.5f (1.0 default)
    var saturation: Float = 1.0f,   // 0.0f to 2.5f (1.0 default)
    var warmth: Float = 0f,         // -50f (cool) to +50f (warm)
    var beauty: Float = 0f,         // 0f to 100f (skin smoothing)
    var vignette: Float = 0f,       // 0f to 100f
    var sharpen: Float = 0f,        // 0f to 100f
    var filterPreset: FilterPreset = FilterPreset.NONE
)

enum class FilterPreset(val displayName: String) {
    NONE("Normal"),
    BEAUTY("Percantik (Beauty)"),
    VIVID("Vivid (Segar)"),
    WARM_GOLD("Golden Glow"),
    CYBERPUNK("Cyberpunk Neon"),
    FILM_NOIR("Film Noir (B&W)"),
    RETRO_VINTAGE("Retro Vintage"),
    DRAMATIC_CONTRAST("Kontras Tinggi"),
    CINEMATIC("Sinematik Teal & Orange"),
    PASTEL("Pastel Lembut")
}
