# 🎬 MediaStudio Pro - Studio Editor Foto & Video Lengkap (4K UHD)

[![Android Build APK](https://github.com/udintpp/media-studio-pro/actions/workflows/build.yml/badge.svg)](https://github.com/udintpp/media-studio-pro/actions/workflows/build.yml)
[![Platform](https://img.shields.io/badge/Platform-Android_7.0%2B-brightgreen.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin_1.9-purple.svg)](https://kotlinlang.org)
[![Resolution](https://img.shields.io/badge/Export-Up_to_4K_UHD-blue.svg)](#resolusi-4k-ultra-hd)

**MediaStudio Pro** adalah aplikasi Android lengkap dan powerful untuk mengedit foto dan video dengan fitur profesional: pemisah & pengganti background, pemotong & penyambung video, efek percantik, kontras & kecerahan, konversi foto ke video, ekstraksi frame video resolusi tinggi, rotasi media, mixer & efek audio komprehensif, pencarian emotikon & stiker online, serta dukungan ekspor hingga **4K Ultra HD (3840x2160)**.

---

## 🌟 Fitur Utama & Fungsionalitas

### 1. ✂️ Hapus & Ganti Background (Photo & Video)
- **Foto:** Deteksi warna tepi & ambang warna dinamis untuk menghasilkan potongan gambar transparan (PNG).
- **Ganti Background:** Ganti latar belakang dengan warna solid (Putih, Hitam, Neon, dll), gradasi, atau gambar custom dari galeri Anda.
- **Video:** Fitur Chroma Key untuk mengganti background video (Green/Blue screen).

### 2. ✂️ Potong dan Sambung (Video Cutter & Concatenator)
- **Potong (Trim):** Pemotongan video presisi hingga milidetik dengan slider dua arah dan seekbar interaktif.
- **Sambung (Merge/Concat):** Gabungkan beberapa klip video berbeda urutan menjadi satu video utuh tanpa penurunan kualitas.

### 3. ✨ Percantik, Kontras, dan Kecerahan
- **Percantik Kulit (Beauty Filter):** Menghaluskan tekstur kulit secara natural dengan glow lembut.
- **Kecerahan & Kontras:** Slider kecerahan (-100 s/d +100) dan kontras (0.2x s/d 2.5x).
- **Saturasi & Warmth:** Atur kejernihan warna dan temperatur hangat/dingin.
- **Preset Filter Artistik:** Normal, Beauty Glow, Vivid, Cyberpunk Neon, Film Noir (B&W), Retro Vintage, Sinematik Teal & Orange, dan Pastel.
- **Ketajaman (Sharpen) & Vignette:** Efek tepi sinematik dan penajaman detail objek.

### 4. 📺 Resolusi Hingga 4K Ultra HD
- Pilihan resolusi ekspor multi-level:
  - `480p SD` (854 x 480)
  - `720p HD` (1280 x 720)
  - `1080p Full HD` (1920 x 1080)
  - `2K QHD` (2560 x 1440)
  - `4K Ultra HD` (3840 x 2160)
  - `Resolusi Asli (Original)`

### 5. 🎞️ Convert Foto ke Video (Slideshow Studio)
- Ubah kumpulan foto galeri menjadi video MP4 berkualitas tinggi.
- Efek animasi **Ken Burns (Smooth Zoom & Pan)** otomatis.
- Pengaturan durasi per foto (1.0 s/d 10.0 detik).
- Integrasi audio/musik lagu latar yang selaras.

### 6. 🖼️ Ekstrak Frame dari Video
- Tangkap momen spesifik dari video dalam resolusi asli/4K secara instan.
- Slider pencari waktu (seek) presisi milidetik.
- Mode **Ekstrak Beruntun:** Mengambil gambar secara periodik (setiap 1.5 detik) ke galeri frame foto.

### 7. 🔄 Rotasi & Flip Foto dan Video
- Rotasi 90°, 180°, 270°.
- Flip Horizontal (Cermin / Mirror) dan Flip Vertikal.
- Crop aspect ratio: 1:1 (Persegi), 4:5 (Instagram), 9:16 (Story/TikTok/Reels), 16:9 (YouTube), 4:3 (Standar).

### 8. 🔍 Pencarian Emotikon & Stiker Google / Web
- Database emotikon Unicode terintegrasi dengan pencarian kata kunci multi-bahasa (Indonesia & Inggris).
- Pencarian online stiker web & GIF transparan langsung dari keyword (misalnya: "fire", "sparkle", "cat", "party").
- Kontrol sentuh intuitif: geser (drag), perbesar/perkecil (pinch-to-zoom), putar dua jari (rotate), dan hapus stiker aktif.

### 9. 🎵 Mixer Audio & Video serta Efek Audio Lengkap
- **Multi-Track Mixing:** Atur volume suara video asli (0% - 200%) atau aktifkan mode Mute (Bisu).
- **Ganti Audio Track:** Tambahkan musik latar (BGM) dengan volume terpisah.
- **Efek Speed & Tempo:** Perlambat video (Slow Motion 0.5x, 0.75x) atau percepat (1.25x, 1.5x, 2.0x).
- **Pitch Shift:** Ubah tinggi/rendah nada (Preset Suara Chipmunk & Suara Berat Deep Voice).
- **Equalizer Presets:** Bass Booster, Vocal Enhance, Treble Boost.

### 10. 📷 Kamera & Galeri
- Tangkap langsung foto dan rekaman video baru melalui kamera bawaan aplikasi.
- Buka file foto, video, dan audio langsung dari penyimpanan/galeri HP.

---

## 🛠️ Arsitektur Teknologi

- **Bahasa:** Kotlin 1.9.23
- **Android Target:** SDK 34 (Android 14) / Min SDK 24 (Android 7.0+)
- **UI:** Material Design 3 (Dark AMOLED Mode) + ViewBinding
- **Video & Audio Pipeline:** Android Media3 (ExoPlayer 1.3.1, Transformer, Effects, SonicAudioProcessor) & Android Native MediaCodec/MediaExtractor/MediaMuxer
- **Grafis & Gambar:** Android 2D Canvas Hardware Accelerated, OpenGL ES Shaders, ColorMatrix, Convolution Kernel, Glide 4.16.0
- **CI/CD:** GitHub Actions dengan Java 17 + Gradle 8.7 (Build APK & Rilis Otomatis)

---

## 🚀 Cara Build & Mengembangkan

Clone repositori ini:
```bash
git clone https://github.com/udintpp/media-studio-pro.git
cd media-studio-pro
```

Build debug APK menggunakan Gradle:
```bash
./gradlew assembleDebug
```

File APK akan dibuat di:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 Lisensi

Didistribusikan di bawah lisensi MIT.
Dikembangkan untuk pengguna Android oleh **udintpp**.
