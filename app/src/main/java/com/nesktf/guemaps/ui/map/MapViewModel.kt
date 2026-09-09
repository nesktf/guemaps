package com.nesktf.guemaps.ui.map

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.model.BusCategory
import com.nesktf.guemaps.data.model.BusGroupNode
import com.nesktf.guemaps.data.model.BusLiveDetails
import com.nesktf.guemaps.data.model.BusNode
import com.nesktf.guemaps.data.model.BusPos
import com.nesktf.guemaps.data.model.FlatBusLine
import com.nesktf.guemaps.data.remote.SaetaApiClient
import com.nesktf.guemaps.data.repository.BusRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MapUiState(
    val isLoadingGroups: Boolean = false,
    val busGroups: BusGroupNode? = null,
    val categories: List<BusCategory> = emptyList(),
    val selectedCategory: String? = null, // null = all
    val expandedSubgroups: Set<String> = emptySet(),
    val favoriteLines: List<FlatBusLine> = emptyList(),
    val favoriteLineCodes: Set<String> = emptySet(),
    val flatLines: List<FlatBusLine> = emptyList(),
    val filteredLines: List<FlatBusLine> = emptyList(),
    val selectedLine: FlatBusLine? = null,
    val isLoadingRoute: Boolean = false,
    val routeNodes: List<BusNode> = emptyList(),
    val isRouteOffline: Boolean = false,
    val isGroupsOffline: Boolean = false,
    val activeBuses: List<BusPos> = emptyList(),
    val busLiveDetails: Map<String, BusLiveDetails> = emptyMap(),
    val selectedBusInterno: String? = null,
    val userLocation: Location? = null,
    val isLocatingUser: Boolean = false,
    val isSpeedCardExpanded: Boolean = true,
    val isLoadingBuses: Boolean = false,
    val busesErrorMessage: String? = null,
    val errorMessage: String? = null,
    val searchQuery: String = "",
    val isLinePickerOpen: Boolean = false,
    val showStops: Boolean = true
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val POLLING_INTERVAL_MS = 10_000L // 10 seconds polling interval
        private const val PREFS_NAME = "guemaps_prefs"
        private const val KEY_LAST_LINE_CODE = "last_line_code"
        private const val KEY_LAST_LINE_DESC = "last_line_desc"
        private const val KEY_LAST_LINE_PATH = "last_line_path"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val repository: BusRepository
    private var busPollingJob: Job? = null
    private val previousPositions = mutableMapOf<String, Pair<BusPos, Long>>()
    private val locationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var locationListener: LocationListener? = null

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        val database = GuemapsDatabase(application)
        val apiClient = SaetaApiClient()
        repository = BusRepository(apiClient, database)

        // Load favorites
        loadFavoriteLines()

        // Restore last selected line on boot
        val lastSelected = getSavedSelectedLine()
        if (lastSelected != null) {
            _uiState.update { it.copy(selectedLine = lastSelected, isLoadingRoute = true) }
            loadRouteForLine(lastSelected.codLinea)
            startActiveBusesPolling(lastSelected.codLinea)
        }

        loadBusGroups()
    }

    fun loadFavoriteLines() {
        viewModelScope.launch {
            val favs = repository.getFavoriteLines()
            _uiState.update {
                it.copy(
                    favoriteLines = favs,
                    favoriteLineCodes = favs.map { line -> line.codLinea }.toSet()
                )
            }
        }
    }

    fun toggleFavoriteLine(line: FlatBusLine) {
        viewModelScope.launch {
            repository.toggleFavoriteLine(line)
            loadFavoriteLines()
        }
    }

    private fun saveSelectedLine(line: FlatBusLine) {
        prefs.edit()
            .putString(KEY_LAST_LINE_CODE, line.codLinea)
            .putString(KEY_LAST_LINE_DESC, line.descripcion)
            .putString(KEY_LAST_LINE_PATH, line.groupPath)
            .apply()
    }

    private fun getSavedSelectedLine(): FlatBusLine? {
        val code = prefs.getString(KEY_LAST_LINE_CODE, null) ?: return null
        val desc = prefs.getString(KEY_LAST_LINE_DESC, "") ?: ""
        val path = prefs.getString(KEY_LAST_LINE_PATH, "") ?: ""
        return FlatBusLine(groupPath = path, codLinea = code, descripcion = desc)
    }

    fun loadBusGroups(forceNetwork: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingGroups = true, errorMessage = null) }
            val result = repository.getBusGroups(forceNetwork = forceNetwork)
            result.onSuccess { groupsResult ->
                val cats = groupsResult.rootGroup.extractCategories()

                // Only expand the subgroup that contains the selected bus line on boot
                val currentLineCode = _uiState.value.selectedLine?.codLinea
                val bootSubgroup = if (currentLineCode != null) {
                    cats.flatMap { cat ->
                        cat.subgroups.filter { sub -> sub.lines.any { it.codLinea == currentLineCode } }
                            .map { "${cat.name}::${it.subgroupName}" }
                    }.toSet()
                } else {
                    emptySet()
                }

                _uiState.update { state ->
                    val filtered = filterLines(groupsResult.flatLines, state.searchQuery)
                    state.copy(
                        isLoadingGroups = false,
                        busGroups = groupsResult.rootGroup,
                        categories = cats,
                        expandedSubgroups = if (state.expandedSubgroups.isEmpty()) bootSubgroup else state.expandedSubgroups,
                        flatLines = groupsResult.flatLines,
                        filteredLines = filtered,
                        isGroupsOffline = groupsResult.isFromCache,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoadingGroups = false,
                        errorMessage = error.localizedMessage ?: "Error al cargar líneas de colectivos"
                    )
                }
            }
        }
    }

    fun selectLine(line: FlatBusLine) {
        saveSelectedLine(line)
        _uiState.update {
            it.copy(
                selectedLine = line,
                isLinePickerOpen = false,
                isLoadingRoute = true,
                errorMessage = null,
                activeBuses = emptyList()
            )
        }
        loadRouteForLine(line.codLinea)
        startActiveBusesPolling(line.codLinea)
    }

    private fun loadRouteForLine(lineId: String, forceNetwork: Boolean = false) {
        viewModelScope.launch {
            val result = repository.getBusRoute(lineId, forceNetwork = forceNetwork)
            result.onSuccess { routeResult ->
                _uiState.update {
                    it.copy(
                        isLoadingRoute = false,
                        routeNodes = routeResult.nodes,
                        isRouteOffline = routeResult.isFromCache
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoadingRoute = false,
                        errorMessage = error.localizedMessage ?: "Error al cargar recorrido"
                    )
                }
            }
        }
    }

    private fun startActiveBusesPolling(lineId: String) {
        busPollingJob?.cancel()
        busPollingJob = viewModelScope.launch {
            while (isActive) {
                fetchActiveBusesInternal(lineId)
                delay(10_000) // Poll every 10 seconds
            }
        }
    }

    fun refreshActiveBuses() {
        val currentLine = _uiState.value.selectedLine ?: return
        viewModelScope.launch {
            fetchActiveBusesInternal(currentLine.codLinea)
        }
    }

    private suspend fun fetchActiveBusesInternal(lineId: String) {
        _uiState.update { it.copy(isLoadingBuses = true, busesErrorMessage = null) }
        val result = repository.getActiveBuses(lineId)
        result.onSuccess { buses ->
            val detailsMap = computeLiveDetails(buses)
            _uiState.update {
                it.copy(
                    isLoadingBuses = false,
                    activeBuses = buses,
                    busLiveDetails = detailsMap,
                    busesErrorMessage = null
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    isLoadingBuses = false,
                    busesErrorMessage = if (it.isRouteOffline) "Sin conexión para colectivos en vivo" else (error.localizedMessage ?: "Error al actualizar colectivos")
                )
            }
        }
    }

    private fun computeLiveDetails(
        buses: List<BusPos>,
        now: Long = System.currentTimeMillis()
    ): Map<String, BusLiveDetails> {
        val stops = _uiState.value.routeNodes.filter { it.parada }
        val userLoc = _uiState.value.userLocation

        // Find the bus stop on this route closest to the USER
        val userNearestStop = if (userLoc != null && stops.isNotEmpty()) {
            stops.minByOrNull { calculateDistanceMeters(userLoc.latitude, userLoc.longitude, it.latitud, it.longitud) }
        } else null

        val detailsMap = mutableMapOf<String, BusLiveDetails>()

        buses.forEach { bus ->
            val prev = previousPositions[bus.interno]
            var speed: Double? = null
            if (prev != null) {
                val dtHours = (now - prev.second) / (1000.0 * 3600.0)
                if (dtHours in 0.0008..0.05) { // between ~3s and ~3min
                    val distKm = calculateDistanceMeters(prev.first.latitud, prev.first.longitud, bus.latitud, bus.longitud) / 1000.0
                    val calculated = distKm / dtHours
                    if (calculated in 0.0..140.0) {
                        speed = calculated
                    }
                }
            }
            previousPositions[bus.interno] = Pair(bus, now)

            // 1. Distance between this bus and the closest bus stop to the user!
            var userStopDist: Double? = null
            var userStopName: String? = null
            if (userNearestStop != null) {
                userStopDist = calculateDistanceMeters(bus.latitud, bus.longitud, userNearestStop.latitud, userNearestStop.longitud)
                userStopName = userNearestStop.descripcionParada?.takeIf { it.isNotBlank() } ?: userNearestStop.codigoParada
            }

            // 2. Distance to nearest stop of the bus itself (fallback)
            var busStopName: String? = null
            var busStopDist: Double? = null
            if (stops.isNotEmpty()) {
                val nearestToBus = stops.minByOrNull { calculateDistanceMeters(bus.latitud, bus.longitud, it.latitud, it.longitud) }
                if (nearestToBus != null) {
                    busStopName = nearestToBus.descripcionParada?.takeIf { it.isNotBlank() } ?: nearestToBus.codigoParada
                    busStopDist = calculateDistanceMeters(bus.latitud, bus.longitud, nearestToBus.latitud, nearestToBus.longitud)
                }
            }

            detailsMap[bus.interno] = BusLiveDetails(
                bus = bus,
                speedKmh = speed,
                userNearestStopName = userStopName,
                distanceToUserStopMeters = userStopDist,
                nearestStopName = busStopName ?: bus.proximaParada,
                distanceToNearestStopMeters = busStopDist
            )
        }

        return detailsMap
    }

    private fun recalculateBusLiveDetails() {
        val current = _uiState.value.activeBuses
        if (current.isNotEmpty()) {
            val details = computeLiveDetails(current)
            _uiState.update { it.copy(busLiveDetails = details) }
        }
    }

    fun requestUserLocation(onLocationReady: ((Location) -> Unit)? = null) {
        val hasFine = ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return

        _uiState.update { it.copy(isLocatingUser = true) }

        // Check if last known location is available
        val providers = listOfNotNull(
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else null,
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else null
        )

        var bestLast: Location? = null
        for (p in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(p)
                if (loc != null && (bestLast == null || loc.time > bestLast.time)) {
                    bestLast = loc
                }
            } catch (_: SecurityException) {}
        }

        if (bestLast != null) {
            setUserLocationInternal(bestLast)
            onLocationReady?.invoke(bestLast)
        }

        locationListener?.let {
            try { locationManager.removeUpdates(it) } catch (_: SecurityException) {}
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                setUserLocationInternal(location)
                _uiState.update { it.copy(isLocatingUser = false) }
                onLocationReady?.invoke(location)
                try {
                    locationManager.removeUpdates(this)
                } catch (_: SecurityException) {}
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
        }
        locationListener = listener

        for (p in providers) {
            try {
                locationManager.requestLocationUpdates(
                    p,
                    1000L,
                    1f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (_: SecurityException) {}
        }

        // Safety timeout of 8 seconds
        viewModelScope.launch {
            delay(8000)
            if (_uiState.value.isLocatingUser) {
                _uiState.update { it.copy(isLocatingUser = false) }
                locationListener?.let {
                    try { locationManager.removeUpdates(it) } catch (_: SecurityException) {}
                }
            }
        }
    }

    private fun setUserLocationInternal(location: Location) {
        _uiState.update { it.copy(userLocation = location) }
        recalculateBusLiveDetails()
    }

    fun selectBusForFloatingCard(interno: String) {
        _uiState.update { it.copy(selectedBusInterno = interno) }
    }

    fun toggleSpeedCardExpanded() {
        _uiState.update { it.copy(isSpeedCardExpanded = !it.isSpeedCardExpanded) }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredLines = filterLines(state.flatLines, query)
            )
        }
    }

    private fun filterLines(lines: List<FlatBusLine>, query: String): List<FlatBusLine> {
        if (query.isBlank()) return lines
        val clean = query.trim().lowercase()
        return lines.filter {
            it.descripcion.lowercase().contains(clean) ||
            it.codLinea.contains(clean) ||
            it.groupPath.lowercase().contains(clean)
        }
    }

    fun setLinePickerOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isLinePickerOpen = isOpen) }
    }

    fun selectCategory(categoryName: String?) {
        _uiState.update { it.copy(selectedCategory = categoryName) }
    }

    fun toggleSubgroupExpanded(subgroupKey: String) {
        _uiState.update { state ->
            val updated = if (state.expandedSubgroups.contains(subgroupKey)) {
                state.expandedSubgroups - subgroupKey
            } else {
                state.expandedSubgroups + subgroupKey
            }
            state.copy(expandedSubgroups = updated)
        }
    }

    fun toggleStops() {
        _uiState.update { it.copy(showStops = !it.showStops) }
    }

    override fun onCleared() {
        super.onCleared()
        busPollingJob?.cancel()
        locationListener?.let {
            try { locationManager.removeUpdates(it) } catch (_: SecurityException) {}
        }
    }
}
