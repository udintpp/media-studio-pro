# 🎬 MediaStudio Pro - Studio Editor Foto & Video Lengkap (4K UHD)

[![Android Build APK](https://github.com/udintpp/media-studio-pro/actions/workflows/build.yml/badge.svg)](https://github.com/udintpp/media-studio-pro/actions/workflows/build.yml)
[![Platform](https://img.shields.io/badge/Platform-Android_7.0%2B-brightgreen.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin_1.9-purple.svg)](https://kotlinlang.org)
[![Resolution](https://img.shields.io/badge/Export-Up_to_4K_UHD-blue.svg)](#resolusi-4k-ultra-hd)

**MediaStudio Pro** adalah aplikasi Android lengkap dan powerful untuk mengedit foto dan video dengan fitur profesional: pemisah & pengganti background, pemotong & penyambung video, efek percantik, kontras & kecerahan, konversi foto ke video, ekstraksi frame video resolusi tinggi, rotasi media, mixer & efek audio komprehensif, pencarian emotikon & stiker online, serta dukungan ekspor hingga **4K Ultra HD (3840x2160)**.

---

## 🌟 Fitur Utama & Fungsionalitas

### 1. ✂️ Hapus & Ganti Background (Photo & Video Transparan Penuh)
- **Foto:** Saat background foto dihapus (AI Deteksi Orang Presisi, Flood Fill, Pipet Warna, atau Kuas Hapus), latar belakang otomatis menjadi **transparan murni (100% Alpha)** dan menampilkan pola papan catur (*transparency checkerboard*). Latar belakang pengganti HANYA muncul jika pengguna memilih ganti background.
- **Ganti Background Foto:** Ganti latar belakang dengan warna solid (Putih, Hitam, Neon, Studio), atau foto custom dari galeri. Tombol **✨ BG Transparan Murni** mengembalikan ke transparan kapan saja.
- **Video Chroma Key Transparan:** Tandai warna latar video dengan Pipet atau preset Layar Hijau (*Green Screen*) / Layar Biru (*Blue Screen*) untuk dihapus jadi transparan (WebM VP9 dengan Alpha / Animated GIF).
- **Ganti Background Video:** Dukungan mengganti latar belakang video dengan Foto dari Galeri, Video lain dari Galeri, atau Warna Solid baru, lengkap dengan thumbnail pratinjau langsung (*Live Preview*).

### 2. 📁 Format Export Lengkap (Foto & Video)
- **Format Foto Terlengkap:**
  - `PNG` (Lossless, Mendukung Transparansi Penuh)
  - `JPG / JPEG` (Standar Foto)
  - `WEBP` (Modern Google Web, Transparan & Hemat Ukuran)
  - `GIF` (Grafis / Animasi Transparan)
  - `ICO` (Windows Icon Format 32-bit ARGB Transparan)
  - `PSD` (Adobe Photoshop Document Multi-Channel Alpha)
  - `PCX` (ZSoft PC Paintbrush 24-bit RGB)
  - `SVG & AVG` (Scalable Vector Graphics XML & Android Vector Drawable XML)
  - `BMP` (Windows Bitmap 32-bit ARGB)
  - `TIFF` (Tagged Image File Format Resolusi Tinggi)
- **Format Video Terlengkap:**
  - `MP4` (Standar H.264 / H.265 Ultra HD hingga 4K)
  - `MOV` (Apple QuickTime Format)
  - `WEBM` (VP8 / VP9, Mendukung Video Transparan)
  - `MKV` (Matroska Multimedia Container)
  - `AVI` (Audio Video Interleave)
  - `3GP` (Mobile Format Ringan)
  - `GIF Animasi Video` (Cuplikan video bergerak transparan)

### 3. ⚡ Adobe After Effects Studio Pro (Motion Graphics, Customization & VFX Engine)
- **🎬 Pemutaran Pratinjau Video Bergerak Real-time (Live Video Playback Engine):** Integrasi pemutar hardware-accelerated **TextureView** dan **MediaPlayer** yang memutar video bergerak halus 30-60 fps lengkap dengan audio sinkron saat preview tombol play ditekan. Dilengkapi arsitektur *double-buffering zero-GC frame compositor* untuk merender efek visual After Effects di atas video yang sedang bergerak secara instan.
- **⚙️ Kustom Durasi Komposisi (Custom Duration):** Pengaturan durasi komposisi bebas: preset cepat (3s, 5s, 10s, 15s, 30s, 60s) atau input manual hingga 300 detik dengan sinkronisasi langsung pada timeline seekbar dan export rendering.
- **📐 Kustom Resolusi & Rasio Aspek Layar:** Rasio standar industri: **16:9 Landscape (YouTube)**, **9:16 Vertikal (TikTok/Reels/Shorts)**, **1:1 Persegi (Instagram)**, **4:5 Sinematik Portrait**, dan **21:9 Ultrawide Bioskop**, serta opsi resolusi render hingga **4K UHD (3840x2160)**.
- **🎛️ Parameter Partikel & Kecepatan Efek Global:**
  - *Kecepatan Efek Global:* Pengatur laju waktu efek 0.25x hingga 3.0x.
  - *Jumlah Partikel (Density):* 15 hingga 200 partikel (Sparks, Rain, Snow, Bokeh).
  - *Kecepatan Partikel (Velocity):* 0.2x (Super Slow) hingga 3.0x (Hiper).
  - *Ukuran Butiran Partikel:* 0.5x hingga 2.0x.
- **✨ Motion Text Huruf Timbul 3D & Tipografi Lengkap:**
  - *Efek Huruf Timbul 3D (3D Extruded Bevel):* Multi-layer bevel extrusion depth (3px hingga 20px) dengan garis tepi kontur luar dan soft shadow.
  - *Pilihan Font Typography:* Monospace Hacker, Serif Sinematik, Sans-Serif Modern, Casual Script, dan Default Bold.
  - *Warna Huruf & Ukuran:* Palet warna lengkap (Putih, Emas, Cyan Neon, Magenta, Hijau Limau, Merah Crimson, Hitam) dan ukuran text 24sp hingga 110sp.
  - *7 Gaya Animasi Motion Text:* Typewriter (mesin tik), Kinetic Zoom Pop, Neon Pulse, Cyber Glitch, 3D Space Flip, Wave Bounce (gelombang pantul), Fade & Slide.
- **🎉 Stiker & Emoji Animasi Interaktif:**
  - Koleksi stiker emoji dinamis: 🔥, ⚡, ✨, 🎬, 🚀, ❤️, 👑, 💯, 💥, 🌟, 🕶️, 🏆, 💎, 🍿, 🎯, 💣, 💫, 🪐, 🛸, 🎮.
  - 6 Mode Animasi: **Denyut (Pulse)**, **Melompat Pantul (Bounce)**, **Berputar 360° (Spin)**, **Goyang (Wobble)**, **Mengapung Melayang (Float)**, atau Diam (None).
  - Skala ukuran stiker (0.6x hingga 2.5x).
- **📍 Reposisi Bebas Touch Drag di Layar Preview:** Sentuh dan seret langsung teks judul atau stiker di atas layar pratinjau (*live canvas*) untuk mengatur letak posisi secara instan dan bebas.
- **🧊 3D Layer Space (After Effects 3D Switch):** Transformasi layer dalam ruang 3 dimensi menggunakan matriks kamera perspektif: **3D Tilt X (Pitch)**, **3D Pan Y (Yaw)**, dan jarak kamera kedalaman Z.
- **🎭 Masking & Rotoscoping:** Isolasi area dengan Shape Masks (**Kotak Persegi / Lingkaran Oval**), **Mask Feathering** (tepi halus), dan **Invert Mask** (pembalikan area dalam/luar).
- **⚡ Speed Ramping & Time Remapping:** Kontrol kecepatan klip dinamis: Super Slow-Mo (0.25x), Slow Motion Sinematik (0.5x), Kecepatan Normal (1.0x), Cepat (1.5x), Hyperlapse (2.0x), dan **Putar Mundur (Reverse Time)**.
- **📈 Keyframe Animation & Easing Curves:** Sisipkan titik keyframe dinamis untuk Posisi, Skala, Rotasi, 3D Tilt/Pan, dan Opasitas dengan kurva easing: **Easy-Ease (Hermite)**, **Linear**, **Ease-In**, **Ease-Out**, **Bounce (Pegas)**, dan **Elastic (Osilasi)**.
- **☀️ CC Light Rays (Volumetric God Rays):** Pancaran sinar cahaya volumetrik berkilau memancar dramatis dari pusat hotspot pencahayaan dengan rotasi angular dinamis.
- **🌊 Turbulent Displace (Liquify):** Distorsi riak gelombang cairan, efek panas atmosferik, dan asap organik dinamis khas After Effects.
- **🌀 CC Radial Fast Blur (Zoom Blur):** Efek zoom burst radial eksplosif ke arah pusat untuk transisi aksi berkecepatan tinggi.
- **⚡ Saber Neon Edge & Contour Glow:** Efek garis kontur tepi laser berenergi tinggi bercahaya neon ala plugin Video Copilot Saber.
- **🎨 Lumetri Color Curves:** Grading warna sinematik Hollywood: **Teal & Orange Blockbuster**, **Cyberpunk Neon**, **Film Noir B&W Dramatis**, **Matrix Emerald**, dan **Vintage Golden Hour**.
- **🌟 Optical Deep Glow:** Pendaran cahaya optik neon pekat (*radiance glow*) dengan ambang batas dinamis dan radius difusi bioskop.
- **⚡ RGB Split & Glitch:** Aberasi kromatik kanal Red dan Cyan terpisah dengan horizontal scanlines cyberpunk.
- **📳 Camera Wiggle Shake (`wiggle(freq, amp)`):** Simulasi goyangan kamera genggam organik natural dengan pengaturan frekuensi (Hz) dan amplitudo getaran.
- **☀️ Anamorphic Lens Flare Sinematik:** Kilatan lensa bioskop Hollywood dengan streak horizontal cyan/emas, cincin refleksi optik sekunder, dan hotspot glare.
- **✨ Particle World Simulation:**
  - *Sparks & Embers:* Percikan api dan bara melayang dinamis dengan gravitasi fisik.
  - *Rain & Mist:* Simulasi atmosferik tetesan hujan deras dengan kabut.
  - *Snow Drift:* Butiran salju melayang lembut dengan efek turbulensi angin acak.
  - *Bokeh Orbs:* Lingkaran bias cahaya bokeh aperture lebar dengan kedalaman ruang optik.
- **🎞️ 35mm Celluloid Film Grain:** Tekstur butiran film analog 35mm dengan getaran eksposur halus.
- **🎵 Audio Waveform Spectrum:** Visualisasi gelombang frekuensi suara neon reaktif terhadap musik.
- **🎭 Layer Blending Modes:** Normal, Add (Linear Dodge), Screen, Multiply, Overlay, Lighten, Darken, Difference / XOR.

### 4. ✂️ Potong dan Sambung (Video Cutter & Concatenator)
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
