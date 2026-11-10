package com.udintpp.mediastudio.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.udintpp.mediastudio.databinding.ActivityMainBinding
import com.udintpp.mediastudio.ui.audio.AudioMixerActivity
import com.udintpp.mediastudio.ui.cutter.VideoCutterConcatActivity
import com.udintpp.mediastudio.ui.frame.FrameExtractorActivity
import com.udintpp.mediastudio.ui.photo.PhotoEditorActivity
import com.udintpp.mediastudio.ui.slideshow.SlideshowActivity
import com.udintpp.mediastudio.ui.vfx.AfterEffectsStudioActivity
import com.udintpp.mediastudio.ui.video.VideoEditorActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var tempCameraUri: Uri? = null

    // Permission launcher
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Izin akses dibutuhkan untuk fitur studio media", Toast.LENGTH_SHORT).show()
        }
    }

    // Camera Photo
    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempCameraUri != null) {
            val intent = Intent(this, PhotoEditorActivity::class.java).apply {
                data = tempCameraUri
            }
            startActivity(intent)
        }
    }

    // Camera Video
    private val recordVideoLauncher = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success && tempCameraUri != null) {
            val intent = Intent(this, VideoEditorActivity::class.java).apply {
                data = tempCameraUri
            }
            startActivity(intent)
        }
    }

    // Gallery Picker for Photo
    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val intent = Intent(this, PhotoEditorActivity::class.java).apply {
                data = it
            }
            startActivity(intent)
        }
    }

    // Gallery Picker for Video
    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val intent = Intent(this, VideoEditorActivity::class.java).apply {
                data = it
            }
            startActivity(intent)
        }
    }

    // Gallery Picker for After Effects Studio (Photo or Video)
    private val pickAfterEffectsLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val intent = Intent(this, AfterEffectsStudioActivity::class.java).apply {
                data = it
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupListeners()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun setupListeners() {
        // Quick Camera Photo
        binding.btnQuickCameraPhoto.setOnClickListener {
            launchCameraForPhoto()
        }

        // Quick Camera Video
        binding.btnQuickCameraVideo.setOnClickListener {
            launchCameraForVideo()
        }

        // Quick Gallery
        binding.btnQuickGallery.setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }

        // Feature 1: Photo Studio Pro
        binding.cardPhotoEditor.setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }

        // Feature 2: Video Studio Pro
        binding.cardVideoEditor.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        // Feature: After Effects Studio Pro
        binding.cardAfterEffects.setOnClickListener {
            pickAfterEffectsLauncher.launch("*/*")
        }

        // Feature 3: Slideshow (Foto ke Video)
        binding.cardSlideshow.setOnClickListener {
            startActivity(Intent(this, SlideshowActivity::class.java))
        }

        // Feature 4: Potong & Sambung Video
        binding.cardCutConcat.setOnClickListener {
            startActivity(Intent(this, VideoCutterConcatActivity::class.java))
        }

        // Feature 5: Ekstrak Frame Video
        binding.cardFrameExtract.setOnClickListener {
            startActivity(Intent(this, FrameExtractorActivity::class.java))
        }

        // Feature 6: Mixer & Efek Audio
        binding.cardAudioMixer.setOnClickListener {
            startActivity(Intent(this, AudioMixerActivity::class.java))
        }
    }

    private fun launchCameraForPhoto() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(cacheDir, "CameraPhoto_$timeStamp.jpg")
        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        tempCameraUri = uri
        takePhotoLauncher.launch(uri)
    }

    private fun launchCameraForVideo() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(cacheDir, "CameraVideo_$timeStamp.mp4")
        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        tempCameraUri = uri
        recordVideoLauncher.launch(uri)
    }
}
