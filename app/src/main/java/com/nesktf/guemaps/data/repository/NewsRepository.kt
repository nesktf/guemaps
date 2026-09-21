package com.nesktf.guemaps.data.repository

import android.graphics.Bitmap
import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.local.NewsImageCache
import com.nesktf.guemaps.data.model.NewsArticle
import com.nesktf.guemaps.data.model.parseArticleDetailHtml
import com.nesktf.guemaps.data.model.parseNewsListHtml
import com.nesktf.guemaps.data.model.sortedByMostRecent
import com.nesktf.guemaps.data.remote.NewsScraperClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NewsRepository(
    private val client: NewsScraperClient,
    private val database: GuemapsDatabase,
    private val imageCache: NewsImageCache
) {
    companion object {
        const val MAX_ARTICLES_CACHE = 16
    }

    fun getCachedArticles(): List<NewsArticle> {
        return database.getNewsArticles().sortedByMostRecent()
    }

    fun getUnreadCount(): Int {
        return database.getUnreadNewsCount()
    }

    fun markAsRead(id: String) {
        database.markArticleAsRead(id)
    }

    fun markAllAsRead() {
        database.markAllArticlesAsRead()
    }

    fun getImage(id: String): Bitmap? {
        return imageCache.getBitmap(id)
    }

    suspend fun refreshNews(): Result<List<NewsArticle>> = withContext(Dispatchers.IO) {
        val listResult = client.fetchNewsListHtml()
        if (listResult.isFailure) {
            return@withContext Result.failure(listResult.exceptionOrNull() ?: Exception("Unknown error fetching news"))
        }

        val rawHtml = listResult.getOrNull() ?: ""
        val parsed = parseNewsListHtml(rawHtml).sortedByMostRecent().take(MAX_ARTICLES_CACHE)
        if (parsed.isEmpty()) {
            return@withContext Result.success(database.getNewsArticles().sortedByMostRecent())
        }

        // 1. Save list to database (preserves existing read and content state)
        database.saveNewsArticles(parsed, maxKeep = MAX_ARTICLES_CACHE)
        val validIds = parsed.map { it.id }.toSet()
        imageCache.pruneOldImages(validIds)

        // 2. Pre-cache images and details for offline reading
        for (article in parsed) {
            // Cache image if not yet cached
            if (imageCache.getBitmap(article.id) == null && article.imageUrl.isNotBlank()) {
                client.fetchImageBytes(article.imageUrl).onSuccess { bytes ->
                    imageCache.saveImage(article.id, bytes)
                }
            }

            // Pre-fetch article content if not yet cached
            val existing = database.getNewsArticles().find { it.id == article.id }
            if (existing?.contentHtml.isNullOrBlank()) {
                client.fetchArticleDetailHtml(article.id).onSuccess { detailHtml ->
                    val content = parseArticleDetailHtml(detailHtml)
                    if (content.isNotBlank()) {
                        database.updateArticleContent(article.id, content)
                    }
                }
            }
        }

        Result.success(database.getNewsArticles())
    }

    suspend fun loadArticleContent(article: NewsArticle): Result<String> = withContext(Dispatchers.IO) {
        if (!article.contentHtml.isNullOrBlank()) {
            return@withContext Result.success(article.contentHtml)
        }

        val detailResult = client.fetchArticleDetailHtml(article.id)
        if (detailResult.isFailure) {
            return@withContext Result.failure(detailResult.exceptionOrNull() ?: Exception("Error loading article content"))
        }

        val rawHtml = detailResult.getOrNull() ?: ""
        val content = parseArticleDetailHtml(rawHtml)
        if (content.isNotBlank()) {
            database.updateArticleContent(article.id, content)
        }

        Result.success(content)
    }
}
