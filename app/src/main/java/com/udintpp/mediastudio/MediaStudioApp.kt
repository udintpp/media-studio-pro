package com.udintpp.mediastudio

import android.app.Application
import java.io.File

class MediaStudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Prepare working temp directories for media processing
        val tempDir = File(cacheDir, "media_studio_temp")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
    }

    companion object {
        lateinit var instance: MediaStudioApp
            private set
    }
}
