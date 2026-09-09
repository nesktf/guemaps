package com.nesktf.guemaps.ui.balance

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.model.CardBalanceResponse
import com.nesktf.guemaps.data.model.SavedCard
import com.nesktf.guemaps.data.remote.SaetaApiClient
import com.nesktf.guemaps.data.repository.CardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BalanceUiState(
    val cardNumber: String = "",
    val captchaInput: String = "",
    val captchaBitmap: Bitmap? = null,
    val isLoadingCaptcha: Boolean = false,
    val isCheckingBalance: Boolean = false,
    val balanceResponse: CardBalanceResponse? = null,
    val errorMessage: String? = null,
    val recentCards: List<SavedCard> = emptyList(),
    val favoriteCards: List<SavedCard> = emptyList(),
    val favoriteCardNumbers: Set<String> = emptySet()
)

class BalanceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CardRepository

    private val _uiState = MutableStateFlow(BalanceUiState())
    val uiState: StateFlow<BalanceUiState> = _uiState.asStateFlow()

    init {
        val database = GuemapsDatabase(application)
        val apiClient = SaetaApiClient()
        repository = CardRepository(apiClient, database)
        loadRecentCards()
        loadFavoriteCards()
        loadCaptcha()
    }

    fun loadFavoriteCards() {
        viewModelScope.launch {
            val favs = repository.getFavoriteCards()
            _uiState.update {
                it.copy(
                    favoriteCards = favs,
                    favoriteCardNumbers = favs.map { card -> card.cardNumber }.toSet()
                )
            }
        }
    }

    fun toggleFavoriteCard(cardNumber: String, alias: String? = null) {
        viewModelScope.launch {
            repository.toggleFavoriteCard(cardNumber, alias)
            loadFavoriteCards()
        }
    }

    fun loadCaptcha() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCaptcha = true, captchaInput = "") }
            val result = repository.getCaptcha()
            result.onSuccess { bitmap ->
                _uiState.update {
                    it.copy(
                        isLoadingCaptcha = false,
                        captchaBitmap = bitmap,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoadingCaptcha = false,
                        errorMessage = "Error al cargar captcha: ${error.localizedMessage ?: "Verifique su conexión"}"
                    )
                }
            }
        }
    }

    fun onCardNumberChanged(number: String) {
        // Keep only digits
        val filtered = number.filter { it.isDigit() }
        _uiState.update { it.copy(cardNumber = filtered) }
    }

    fun onCaptchaInputChanged(text: String) {
        _uiState.update { it.copy(captchaInput = text) }
    }

    fun selectRecentCard(cardNumber: String) {
        _uiState.update { it.copy(cardNumber = cardNumber) }
    }

    fun loadRecentCards() {
        viewModelScope.launch {
            val recents = repository.getRecentCards()
            _uiState.update { it.copy(recentCards = recents) }
        }
    }

    fun checkBalance() {
        val current = _uiState.value
        if (current.cardNumber.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Ingrese un número de tarjeta") }
            return
        }
        if (current.captchaInput.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Ingrese el código captcha de la imagen") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingBalance = true, errorMessage = null, balanceResponse = null) }
            val result = repository.checkBalance(current.cardNumber, current.captchaInput)
            result.onSuccess { response ->
                if (response.error == 0) {
                    _uiState.update {
                        it.copy(
                            isCheckingBalance = false,
                            balanceResponse = response,
                            errorMessage = null
                        )
                    }
                    loadRecentCards()
                } else {
                    val errorMsg = response.getErrorMessage() ?: "Error al consultar saldo"
                    _uiState.update {
                        it.copy(
                            isCheckingBalance = false,
                            balanceResponse = null,
                            errorMessage = errorMsg
                        )
                    }
                    // If invalid captcha, refresh captcha automatically
                    if (response.error == 1) {
                        loadCaptcha()
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCheckingBalance = false,
                        errorMessage = "Error de conexión: ${error.localizedMessage ?: "Reintente más tarde"}"
                    )
                }
            }
        }
    }
}
