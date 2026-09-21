package com.nesktf.guemaps.ui.balance

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.model.CardBalanceResponse
import com.nesktf.guemaps.data.model.SavedCard
import com.nesktf.guemaps.data.model.TarifaItem
import com.nesktf.guemaps.data.remote.SaetaApiClient
import com.nesktf.guemaps.data.repository.BusRepository
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
    val favoriteCardNumbers: Set<String> = emptySet(),
    val tarifas: List<TarifaItem> = emptyList(),
    val isLoadingTarifas: Boolean = false,
    val tarifasError: String? = null
)

class BalanceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CardRepository
    private val busRepository: BusRepository
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _uiState = MutableStateFlow(BalanceUiState())
    val uiState: StateFlow<BalanceUiState> = _uiState.asStateFlow()

    init {
        val database = GuemapsDatabase(application)
        val apiClient = SaetaApiClient()
        repository = CardRepository(apiClient, database)
        busRepository = BusRepository(apiClient, database)
        loadRecentCards()
        loadFavoriteCards()
        loadCaptcha()
        loadTarifas()
        setupNetworkCallback()
    }

    fun loadTarifas(forceNetwork: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTarifas = true, tarifasError = null) }
            val res = busRepository.getTarifas(forceNetwork)
            res.onSuccess { list ->
                _uiState.update { it.copy(isLoadingTarifas = false, tarifas = list, tarifasError = null) }
            }.onFailure { err ->
                _uiState.update { it.copy(isLoadingTarifas = false, tarifasError = err.localizedMessage) }
            }
        }
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
                        captchaBitmap = bitmap
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

    fun clearBalanceResponse() {
        _uiState.update { it.copy(balanceResponse = null) }
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
            _uiState.update { it.copy(isCheckingBalance = true, balanceResponse = null) }
            val result = repository.checkBalance(current.cardNumber, current.captchaInput)
            result.onSuccess { response ->
                if (response.error == 0) {
                    _uiState.update {
                        it.copy(
                            isCheckingBalance = false,
                            balanceResponse = response,
                            errorMessage = null,
                            captchaInput = ""
                        )
                    }
                    loadRecentCards()
                    loadCaptcha()
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

    private fun setupNetworkCallback() {
        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    loadTarifas(forceNetwork = true)
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
            networkCallback = null
        }
    }
}
