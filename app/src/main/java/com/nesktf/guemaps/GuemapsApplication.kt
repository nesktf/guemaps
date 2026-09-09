package com.nesktf.guemaps

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

class GuemapsApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configure osmdroid tile caching and user agent
        val config = Configuration.getInstance()
        config.userAgentValue = packageName

        val basePath = File(filesDir, "osmdroid")
        val tileCache = File(cacheDir, "osmdroid/tiles")
        if (!basePath.exists()) basePath.mkdirs()
        if (!tileCache.exists()) tileCache.mkdirs()

        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = tileCache
    }
}
