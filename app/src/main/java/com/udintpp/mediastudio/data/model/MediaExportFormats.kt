package com.udintpp.mediastudio.data.model

enum class PhotoExportFormat(
    val extension: String,
    val mimeType: String,
    val displayName: String,
    val supportsTransparency: Boolean,
    val description: String
) {
    PNG(
        extension = "png",
        mimeType = "image/png",
        displayName = "PNG (Lossless)",
        supportsTransparency = true,
        description = "Format standar kualitas tinggi tanpa kompresi pecah. Mendukung transparansi penuh (Alpha Channel)."
    ),
    JPG(
        extension = "jpg",
        mimeType = "image/jpeg",
        displayName = "JPG / JPEG",
        supportsTransparency = false,
        description = "Format foto standar paling populer dan hemat memori. Tidak mendukung transparansi (latar transparan akan diberi warna putih)."
    ),
    WEBP(
        extension = "webp",
        mimeType = "image/webp",
        displayName = "WEBP (Modern)",
        supportsTransparency = true,
        description = "Format gambar modern Google yang sangat ringan dengan kualitas tinggi dan mendukung transparansi."
    ),
    GIF(
        extension = "gif",
        mimeType = "image/gif",
        displayName = "GIF (Grafis / Animasi)",
        supportsTransparency = true,
        description = "Format grafis 256 warna dengan dukungan transparansi penuh untuk stiker dan ikon."
    ),
    ICO(
        extension = "ico",
        mimeType = "image/x-icon",
        displayName = "ICO (Windows Icon)",
        supportsTransparency = true,
        description = "Format ikon resmi Windows dengan transparansi 32-bit ARGB untuk favicon website dan aplikasi desktop."
    ),
    PSD(
        extension = "psd",
        mimeType = "image/vnd.adobe.photoshop",
        displayName = "PSD (Adobe Photoshop)",
        supportsTransparency = true,
        description = "Format dokumen Adobe Photoshop multi-channel dengan channel Alpha transparansi utuh."
    ),
    PCX(
        extension = "pcx",
        mimeType = "image/x-pcx",
        displayName = "PCX (PC Paintbrush)",
        supportsTransparency = false,
        description = "Format klasik ZSoft PC Paintbrush 24-bit RGB dengan kompresi lossless RLE."
    ),
    SVG(
        extension = "svg",
        mimeType = "image/svg+xml",
        displayName = "SVG (Scalable Vector)",
        supportsTransparency = true,
        description = "Format vektor standar World Wide Web Consortium (W3C) berbasis XML dengan transparansi."
    ),
    AVG(
        extension = "xml",
        mimeType = "text/xml",
        displayName = "AVG (Android Vector XML)",
        supportsTransparency = true,
        description = "Format Android Vector Drawable XML resmi untuk resource aplikasi Android modern."
    ),
    BMP(
        extension = "bmp",
        mimeType = "image/bmp",
        displayName = "BMP (Windows Bitmap)",
        supportsTransparency = true,
        description = "Format bitmap Windows 32-bit uncompressed dengan kanal Alpha transparansi."
    ),
    TIFF(
        extension = "tiff",
        mimeType = "image/tiff",
        displayName = "TIFF (Tagged Image)",
        supportsTransparency = true,
        description = "Format fotografi resolusi tinggi standar percetakan profesional dengan transparansi unassociated alpha."
    );

    companion object {
        fun fromExtension(ext: String): PhotoExportFormat {
            return values().firstOrNull { it.extension.equals(ext, ignoreCase = true) } ?: PNG
        }
    }
}

enum class VideoExportFormat(
    val extension: String,
    val mimeType: String,
    val displayName: String,
    val supportsTransparency: Boolean,
    val description: String
) {
    MP4(
        extension = "mp4",
        mimeType = "video/mp4",
        displayName = "MP4 (H.264 / H.265 Ultra HD)",
        supportsTransparency = false,
        description = "Format video standar paling universal, kompatibel dengan semua smartphone, TV, dan media sosial."
    ),
    MOV(
        extension = "mov",
        mimeType = "video/quicktime",
        displayName = "MOV (Apple QuickTime)",
        supportsTransparency = false,
        description = "Format video kualitas tinggi standar Apple QuickTime dan perangkat iOS/macOS."
    ),
    WEBM(
        extension = "webm",
        mimeType = "video/webm",
        displayName = "WEBM (VP8 / VP9 Video Transparan)",
        supportsTransparency = true,
        description = "Format video modern berbasis Google WebM yang mendukung video dengan background transparan (Alpha Channel)."
    ),
    MKV(
        extension = "mkv",
        mimeType = "video/x-matroska",
        displayName = "MKV (Matroska Multimedia)",
        supportsTransparency = false,
        description = "Format kontainer Matroska fleksibel untuk video definisi tinggi dengan banyak track."
    ),
    AVI(
        extension = "avi",
        mimeType = "video/x-msvideo",
        displayName = "AVI (Audio Video Interleave)",
        supportsTransparency = false,
        description = "Format multimedia klasik Windows untuk kompatibilitas software pemutar video lama."
    ),
    THREE_GP(
        extension = "3gp",
        mimeType = "video/3gpp",
        displayName = "3GP (Mobile Format)",
        supportsTransparency = false,
        description = "Format video ringan dengan ukuran file paling hemat untuk pesan instan dan perangkat mobile."
    ),
    GIF(
        extension = "gif",
        mimeType = "image/gif",
        displayName = "GIF (Animasi Video Transparan)",
        supportsTransparency = true,
        description = "Animasi bergerak tanpa audio dengan latar belakang transparan, cocok untuk stiker WhatsApp & Telegram."
    );

    companion object {
        fun fromExtension(ext: String): VideoExportFormat {
            return values().firstOrNull { it.extension.equals(ext, ignoreCase = true) } ?: MP4
        }
    }
}
