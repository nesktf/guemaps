package com.nesktf.guemaps

import android.app.Application
import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.local.NewsImageCache
import com.nesktf.guemaps.data.remote.NewsScraperClient
import com.nesktf.guemaps.data.remote.SaetaApiClient
import com.nesktf.guemaps.data.repository.BusRepository
import com.nesktf.guemaps.data.repository.CardRepository
import com.nesktf.guemaps.data.repository.NewsRepository
import org.osmdroid.config.Configuration
import java.io.File

class GuemapsApplication : Application() {

    val database: GuemapsDatabase by lazy { GuemapsDatabase(this) }
    val apiClient: SaetaApiClient by lazy { SaetaApiClient() }
    val newsScraperClient: NewsScraperClient by lazy { NewsScraperClient() }
    val newsImageCache: NewsImageCache by lazy { NewsImageCache(this) }

    val busRepository: BusRepository by lazy { BusRepository(apiClient, database) }
    val cardRepository: CardRepository by lazy { CardRepository(apiClient, database) }
    val newsRepository: NewsRepository by lazy { NewsRepository(newsScraperClient, database, newsImageCache) }

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
