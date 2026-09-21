package com.nesktf.guemaps.ui.news

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.local.NewsImageCache
import com.nesktf.guemaps.data.model.NewsArticle
import com.nesktf.guemaps.data.remote.NewsScraperClient
import com.nesktf.guemaps.data.repository.NewsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewsUiState(
    val articles: List<NewsArticle> = emptyList(),
    val selectedArticle: NewsArticle? = null,
    val selectedArticleContent: String? = null,
    val isLoading: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val errorMessage: String? = null
)

class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: NewsRepository
    private val _uiState = MutableStateFlow(NewsUiState())
    val uiState: StateFlow<NewsUiState> = _uiState.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    init {
        val database = GuemapsDatabase(application)
        val scraperClient = NewsScraperClient()
        val imageCache = NewsImageCache(application)
        repository = NewsRepository(scraperClient, database, imageCache)

        // 1. Immediately load cached news from SQLite
        val cached = repository.getCachedArticles()
        _uiState.update { it.copy(articles = cached) }
        _unreadCount.value = repository.getUnreadCount()

        // 2. Refresh news from remote once on boot
        refreshNews()
    }

    fun refreshNews() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.articles.isEmpty(), errorMessage = null) }
            val result = repository.refreshNews()
            result.fold(
                onSuccess = { updatedArticles ->
                    _uiState.update {
                        it.copy(
                            articles = updatedArticles,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                    _unreadCount.value = repository.getUnreadCount()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = if (it.articles.isEmpty()) "No se pudieron cargar las noticias: ${err.localizedMessage}" else null
                        )
                    }
                    _unreadCount.value = repository.getUnreadCount()
                }
            )
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            repository.markAllAsRead()
            _unreadCount.value = 0
            _uiState.update { state ->
                val updated = state.articles.map { it.copy(isRead = true) }
                state.copy(articles = updated)
            }
        }
    }

    fun selectArticle(article: NewsArticle) {
        viewModelScope.launch {
            // Mark as read immediately
            if (!article.isRead) {
                repository.markAsRead(article.id)
                _unreadCount.value = repository.getUnreadCount()
                _uiState.update { state ->
                    val updated = state.articles.map {
                        if (it.id == article.id) it.copy(isRead = true) else it
                    }
                    state.copy(articles = updated)
                }
            }

            _uiState.update {
                it.copy(
                    selectedArticle = article.copy(isRead = true),
                    selectedArticleContent = article.contentHtml,
                    isLoadingDetail = article.contentHtml.isNullOrBlank()
                )
            }

            if (article.contentHtml.isNullOrBlank()) {
                val result = repository.loadArticleContent(article)
                result.fold(
                    onSuccess = { content ->
                        _uiState.update {
                            it.copy(
                                selectedArticleContent = content,
                                isLoadingDetail = false
                            )
                        }
                    },
                    onFailure = {
                        _uiState.update {
                            it.copy(
                                isLoadingDetail = false
                            )
                        }
                    }
                )
            }
        }
    }

    fun clearSelectedArticle() {
        _uiState.update {
            it.copy(
                selectedArticle = null,
                selectedArticleContent = null,
                isLoadingDetail = false
            )
        }
    }

    fun getArticleImage(id: String): Bitmap? {
        return repository.getImage(id)
    }
}
