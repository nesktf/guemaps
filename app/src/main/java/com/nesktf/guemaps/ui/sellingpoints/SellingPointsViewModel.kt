package com.nesktf.guemaps.ui.sellingpoints

import android.app.Application
import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nesktf.guemaps.GuemapsApplication
import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.model.SellingPoint
import com.nesktf.guemaps.data.remote.SaetaApiClient
import com.nesktf.guemaps.data.repository.BusRepository
import com.nesktf.guemaps.ui.map.MapCameraState
import com.nesktf.guemaps.util.LocationTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SellingPointFilter(val label: String) {
    ALL("Todos"),
    ATM("ATMs"),
    COMMERCE("Comercios")
}

private fun normalizeForSearch(text: String): String {
    return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        .lowercase()
        .trim()
}

data class SellingPointsUiState(
    val isLoading: Boolean = true,
    val sellingPoints: List<SellingPoint> = emptyList(),
    val filter: SellingPointFilter = SellingPointFilter.ALL,
    val selectedSellingPoint: SellingPoint? = null,
    val userLocation: Location? = null,
    val initialSortLocation: Location? = null,
    val isLocatingUser: Boolean = false,
    val errorMessage: String? = null,
    val cameraState: MapCameraState = MapCameraState(-24.7859, -65.4117, 14.0),
    val isSearchDrawerOpen: Boolean = false,
    val searchQuery: String = ""
) {
    val filteredPoints: List<SellingPoint>
        get() = when (filter) {
            SellingPointFilter.ALL -> sellingPoints
            SellingPointFilter.ATM -> sellingPoints.filter { it.isAtm }
            SellingPointFilter.COMMERCE -> sellingPoints.filter { !it.isAtm }
        }

    val atmCount: Int get() = sellingPoints.count { it.isAtm }
    val commerceCount: Int get() = sellingPoints.count { !it.isAtm }

    val searchResults: List<SellingPoint>
        get() {
            val sortLoc = initialSortLocation
            val baseList = if (sortLoc != null) {
                sellingPoints.sortedBy { point ->
                    val lat = point.latitud
                    val lon = point.longitud
                    if (lat != null && lon != null) {
                        val res = FloatArray(1)
                        Location.distanceBetween(sortLoc.latitude, sortLoc.longitude, lat, lon, res)
                        res[0]
                    } else {
                        Float.MAX_VALUE
                    }
                }
            } else {
                sellingPoints
            }

            val q = normalizeForSearch(searchQuery)
            if (q.isBlank()) return baseList
            return baseList.filter {
                normalizeForSearch(it.nombre).contains(q) ||
                normalizeForSearch(it.domicilio).contains(q) ||
                normalizeForSearch(it.detalleDomicilio ?: "").contains(q)
            }
        }
}

class SellingPointsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: BusRepository
    private val locationTracker = LocationTracker(application)
    private val connectivityManager: ConnectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _uiState = MutableStateFlow(SellingPointsUiState())
    val uiState: StateFlow<SellingPointsUiState> = _uiState.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        repository = (application as? GuemapsApplication)?.busRepository
            ?: BusRepository(SaetaApiClient(), GuemapsDatabase(application))
        loadSellingPoints()
        startLocationUpdates()
        setupNetworkCallback()
    }

    private fun setUserLocationInternal(location: Location) {
        _uiState.update { state ->
            val sortLoc = state.initialSortLocation ?: location
            state.copy(
                userLocation = location,
                initialSortLocation = sortLoc
            )
        }
    }

    fun loadSellingPoints(forceNetwork: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.getSellingPoints(forceNetwork = forceNetwork)
            result.onSuccess { points ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        sellingPoints = points,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.localizedMessage ?: "Error al cargar puntos de venta"
                    )
                }
            }
        }
    }

    fun setFilter(filter: SellingPointFilter) {
        _uiState.update {
            val selected = it.selectedSellingPoint
            val keepSelected = if (selected != null) {
                when (filter) {
                    SellingPointFilter.ALL -> true
                    SellingPointFilter.ATM -> selected.isAtm
                    SellingPointFilter.COMMERCE -> !selected.isAtm
                }
            } else false
            it.copy(
                filter = filter,
                selectedSellingPoint = if (keepSelected) selected else null
            )
        }
    }

    fun selectSellingPoint(point: SellingPoint?) {
        _uiState.update { it.copy(selectedSellingPoint = point) }
    }

    fun updateMapCamera(lat: Double, lon: Double, zoom: Double) {
        _uiState.update { it.copy(cameraState = MapCameraState(lat, lon, zoom)) }
    }

    fun formatDistanceTo(point: SellingPoint): String? {
        val userLoc = _uiState.value.userLocation ?: return null
        val pLat = point.latitud ?: return null
        val pLon = point.longitud ?: return null

        val results = FloatArray(1)
        Location.distanceBetween(userLoc.latitude, userLoc.longitude, pLat, pLon, results)
        val meters = results[0]

        return if (meters < 1000) {
            "A ${meters.toInt()} m de tu ubicación"
        } else {
            val km = meters / 1000.0
            "A %.1f km de tu ubicación".format(km)
        }
    }

    fun isLocationPermissionGranted(): Boolean = locationTracker.isPermissionGranted()

    fun isLocationProviderEnabled(): Boolean = locationTracker.isProviderEnabled()

    fun startLocationUpdates(onLocationReady: ((Location) -> Unit)? = null) {
        locationTracker.requestLocationUpdates(
            scope = viewModelScope,
            onLocationChanged = { loc ->
                setUserLocationInternal(loc)
                onLocationReady?.invoke(loc)
            },
            onLocatingChanged = { isLocating ->
                _uiState.update { it.copy(isLocatingUser = isLocating) }
            }
        )
    }

    private fun stopLocationUpdates() {
        locationTracker.stopLocationUpdates { isLocating ->
            _uiState.update { it.copy(isLocatingUser = isLocating) }
        }
    }

    fun setSearchDrawerOpen(open: Boolean) {
        _uiState.update { it.copy(isSearchDrawerOpen = open, searchQuery = if (!open) "" else it.searchQuery) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    private fun setupNetworkCallback() {
        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    loadSellingPoints(forceNetwork = true)
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
        stopLocationUpdates()
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
            networkCallback = null
        }
    }
}
