package com.udintpp.mediastudio.data.model

import android.net.Uri

data class AudioMixConfig(
    var originalAudioVolume: Float = 1.0f, // 0.0 (mute) to 2.0 (boost 200%)
    var isOriginalMuted: Boolean = false,
    var bgmUri: Uri? = null,
    var bgmName: String? = null,
    var bgmVolume: Float = 1.0f,          // 0.0 to 2.0
    var bgmOffsetMs: Long = 0L,           // Delay in ms
    var speed: Float = 1.0f,              // 0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x
    var pitchSemitones: Float = 0f,       // -12 to +12
    var bassBoostDb: Float = 0f,          // 0 to +15 dB
    var trebleBoostDb: Float = 0f,        // 0 to +15 dB
    var fadeInSeconds: Float = 0f,        // 0s to 5s
    var fadeOutSeconds: Float = 0f,       // 0s to 5s
    var equalizerPreset: EqualizerPreset = EqualizerPreset.FLAT
)

enum class EqualizerPreset(val label: String) {
    FLAT("Normal (Datar)"),
    BASS_BOOSTER("Bass Booster Ekstra"),
    VOCAL_ENHANCE("Penjernih Vokal"),
    TREBLE_BOOST("Treble Cerah"),
    ELECTRONIC_PUNCH("Electronic / DJ"),
    CHIPMUNK("Suara Chipmunk (Tinggi)"),
    DEEP_VOICE("Suara Berat (Deep Bass)")
}
