package com.nesktf.guemaps.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

class NewsImageCache(context: Context) {
    private val imagesDir = File(context.cacheDir, "news_images").apply {
        if (!exists()) mkdirs()
    }

    // In-memory cache for up to 24 bitmaps
    private val memoryCache = object : LruCache<String, Bitmap>(24) {}

    fun getBitmap(id: String): Bitmap? {
        // Check memory cache
        memoryCache.get(id)?.let { return it }

        // Check disk cache
        val file = File(imagesDir, "$id.jpg")
        if (file.exists() && file.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    memoryCache.put(id, bitmap)
                    return bitmap
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun saveImage(id: String, bytes: ByteArray) {
        try {
            val file = File(imagesDir, "$id.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                memoryCache.put(id, bitmap)
            }
        } catch (_: Exception) {}
    }

    fun pruneOldImages(validIds: Set<String>) {
        try {
            imagesDir.listFiles()?.forEach { file ->
                val id = file.nameWithoutExtension
                if (!validIds.contains(id)) {
                    file.delete()
                    memoryCache.remove(id)
                }
            }
        } catch (_: Exception) {}
    }
}
