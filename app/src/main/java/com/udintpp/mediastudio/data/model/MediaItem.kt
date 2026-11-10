package com.udintpp.mediastudio.data.model

import android.net.Uri

enum class ExportResolution(val label: String, val width: Int, val height: Int, val bitrateMbps: Int) {
    SD_480P("480p SD", 854, 480, 2),
    HD_720P("720p HD", 1280, 720, 5),
    FHD_1080P("1080p Full HD", 1920, 1080, 10),
    QHD_2K("2K QHD (1440p)", 2560, 1440, 18),
    UHD_4K("4K Ultra HD (2160p)", 3840, 2160, 35),
    ORIGINAL("Resolusi Asli (Original)", 0, 0, 25)
}

enum class AspectRatio(val label: String, val ratioX: Float, val ratioY: Float) {
    ORIGINAL("Asli", 0f, 0f),
    SQUARE("1:1 Persegi", 1f, 1f),
    PORTRAIT_4_5("4:5 Instagram", 4f, 5f),
    STORY_9_16("9:16 Story/Reels", 9f, 16f),
    LANDSCAPE_16_9("16:9 YouTube", 16f, 9f),
    CLASSIC_4_3("4:3 Standar", 4f, 3f)
}

data class VideoClip(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    var startTrimMs: Long = 0L,
    var endTrimMs: Long = durationMs
)
