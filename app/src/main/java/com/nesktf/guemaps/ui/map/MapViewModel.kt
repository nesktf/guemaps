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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

val BUS_LINE_PALETTE = listOf(
    "#35399D",
    "#724829",
    "#70B91A",
    "#BE45B4",
    "#F17614",
    "#A12723",
    "#F9C628",
    "#3AAFD9"
)

data class ActiveLineData(
    val line: FlatBusLine,
    val colorHex: String,
    val routeNodes: List<BusNode> = emptyList(),
    val isLoadingRoute: Boolean = false,
    val isRouteOffline: Boolean = false,
    val activeBuses: List<BusPos> = emptyList()
)

data class MapCameraState(
    val centerLat: Double = -24.7859,
    val centerLon: Double = -65.4117,
    val zoomLevel: Double = 15.0
)

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
    val selectedLines: List<FlatBusLine> = emptyList(),
    val activeLines: Map<String, ActiveLineData> = emptyMap(),
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
    val showStops: Boolean = true,
    val cameraState: MapCameraState = MapCameraState(),
    val shouldFitRouteBounds: Boolean = true
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val POLLING_INTERVAL_MS = 10_000L // 10 seconds polling interval
        private const val PREFS_NAME = "guemaps_prefs"
        private const val KEY_LAST_LINE_CODE = "last_line_code"
        private const val KEY_LAST_LINE_DESC = "last_line_desc"
        private const val KEY_LAST_LINE_PATH = "last_line_path"
        private const val KEY_SELECTED_LINES_JSON = "selected_lines_json"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val repository: BusRepository
    private var busPollingJob: Job? = null
    private var locationTimeoutJob: Job? = null
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

        // Restore saved selected lines on boot
        val savedLines = getSavedSelectedLines()
        if (savedLines.isNotEmpty()) {
            var colorIndex = 0
            val activeMap = mutableMapOf<String, ActiveLineData>()
            savedLines.forEach { line ->
                val color = BUS_LINE_PALETTE[colorIndex % BUS_LINE_PALETTE.size]
                colorIndex++
                activeMap[line.codLinea] = ActiveLineData(
                    line = line,
                    colorHex = color,
                    isLoadingRoute = true
                )
            }
            _uiState.update {
                it.copy(
                    selectedLines = savedLines,
                    activeLines = activeMap,
                    selectedLine = savedLines.firstOrNull(),
                    isLoadingRoute = true,
                    shouldFitRouteBounds = true
                )
            }
            savedLines.forEach { line ->
                loadRouteForLine(line.codLinea)
            }
            restartActiveBusesPolling()
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

    private fun assignNextColor(currentActiveLines: Map<String, ActiveLineData>): String {
        val usedColors = currentActiveLines.values.map { it.colorHex }.toSet()
        return BUS_LINE_PALETTE.firstOrNull { it !in usedColors } ?: BUS_LINE_PALETTE.first()
    }

    private fun saveSelectedLines(lines: List<FlatBusLine>) {
        val array = JSONArray()
        lines.forEach { line ->
            val obj = JSONObject().apply {
                put("codLinea", line.codLinea)
                put("descripcion", line.descripcion)
                put("groupPath", line.groupPath)
            }
            array.put(obj)
        }
        prefs.edit()
            .putString(KEY_SELECTED_LINES_JSON, array.toString())
            .putString(KEY_LAST_LINE_CODE, lines.firstOrNull()?.codLinea)
            .putString(KEY_LAST_LINE_DESC, lines.firstOrNull()?.descripcion)
            .putString(KEY_LAST_LINE_PATH, lines.firstOrNull()?.groupPath)
            .apply()
    }

    private fun getSavedSelectedLines(): List<FlatBusLine> {
        val jsonStr = prefs.getString(KEY_SELECTED_LINES_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<FlatBusLine>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        FlatBusLine(
                            groupPath = obj.optString("groupPath", ""),
                            codLinea = obj.getString("codLinea"),
                            descripcion = obj.optString("descripcion", "")
                        )
                    )
                }
                if (list.isNotEmpty()) return list.take(8)
            } catch (_: Exception) {}
        }
        val single = getSavedSelectedLine()
        return if (single != null) listOf(single) else emptyList()
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

                val currentLineCodes = _uiState.value.selectedLines.map { it.codLinea }.toSet()
                val bootSubgroups = if (currentLineCodes.isNotEmpty()) {
                    cats.flatMap { cat ->
                        cat.subgroups.filter { sub -> sub.lines.any { currentLineCodes.contains(it.codLinea) } }
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
                        expandedSubgroups = if (state.expandedSubgroups.isEmpty()) bootSubgroups else state.expandedSubgroups,
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

    fun toggleLineSelection(line: FlatBusLine) {
        val state = _uiState.value
        val isAlreadySelected = state.selectedLines.any { it.codLinea == line.codLinea }

        if (isAlreadySelected) {
            val updatedLines = state.selectedLines.filter { it.codLinea != line.codLinea }
            val updatedActiveMap = state.activeLines - line.codLinea
            saveSelectedLines(updatedLines)

            val allNodes = updatedActiveMap.values.flatMap { it.routeNodes }
            val allBuses = updatedActiveMap.values.flatMap { it.activeBuses }
            val details = computeLiveDetails(allBuses, allNodes)

            _uiState.update {
                it.copy(
                    selectedLines = updatedLines,
                    activeLines = updatedActiveMap,
                    selectedLine = updatedLines.firstOrNull(),
                    routeNodes = allNodes,
                    activeBuses = allBuses,
                    busLiveDetails = details,
                    errorMessage = null
                )
            }
            if (updatedLines.isNotEmpty()) {
                restartActiveBusesPolling()
            } else {
                busPollingJob?.cancel()
            }
        } else {
            if (state.selectedLines.size >= 8) {
                _uiState.update { it.copy(errorMessage = "Máximo 8 líneas permitidas al mismo tiempo") }
                return
            }

            val assignedColor = assignNextColor(state.activeLines)
            val newLineData = ActiveLineData(
                line = line,
                colorHex = assignedColor,
                isLoadingRoute = true
            )
            val updatedLines = state.selectedLines + line
            val updatedActiveMap = state.activeLines + (line.codLinea to newLineData)
            saveSelectedLines(updatedLines)

            _uiState.update {
                it.copy(
                    selectedLines = updatedLines,
                    activeLines = updatedActiveMap,
                    selectedLine = updatedLines.firstOrNull(),
                    errorMessage = null,
                    shouldFitRouteBounds = true
                )
            }

            loadRouteForLine(line.codLinea)
            restartActiveBusesPolling()
        }
    }

    fun selectLine(line: FlatBusLine) {
        toggleLineSelection(line)
    }

    fun removeLine(lineCode: String) {
        val line = _uiState.value.selectedLines.find { it.codLinea == lineCode }
        if (line != null) {
            toggleLineSelection(line)
        }
    }

    private fun loadRouteForLine(lineId: String, forceNetwork: Boolean = false) {
        viewModelScope.launch {
            val result = repository.getBusRoute(lineId, forceNetwork = forceNetwork)
            result.onSuccess { routeResult ->
                _uiState.update { state ->
                    val lineData = state.activeLines[lineId]
                    val updatedLineData = lineData?.copy(
                        isLoadingRoute = false,
                        routeNodes = routeResult.nodes,
                        isRouteOffline = routeResult.isFromCache
                    )
                    val updatedMap = if (updatedLineData != null) {
                        state.activeLines + (lineId to updatedLineData)
                    } else state.activeLines

                    val allNodes = updatedMap.values.flatMap { it.routeNodes }
                    val allOffline = updatedMap.values.any { it.isRouteOffline }
                    val anyLoading = updatedMap.values.any { it.isLoadingRoute }

                    state.copy(
                        activeLines = updatedMap,
                        routeNodes = allNodes,
                        isRouteOffline = allOffline,
                        isLoadingRoute = anyLoading
                    )
                }
                recalculateBusLiveDetails()
            }.onFailure { error ->
                _uiState.update { state ->
                    val lineData = state.activeLines[lineId]
                    val updatedLineData = lineData?.copy(isLoadingRoute = false)
                    val updatedMap = if (updatedLineData != null) {
                        state.activeLines + (lineId to updatedLineData)
                    } else state.activeLines
                    state.copy(
                        activeLines = updatedMap,
                        errorMessage = error.localizedMessage ?: "Error al cargar recorrido"
                    )
                }
            }
        }
    }

    private fun restartActiveBusesPolling() {
        busPollingJob?.cancel()
        val lines = _uiState.value.selectedLines
        if (lines.isEmpty()) return

        busPollingJob = viewModelScope.launch {
            while (isActive) {
                fetchActiveBusesForAllLines()
                delay(10_000) // Poll every 10 seconds
            }
        }
    }

    fun refreshActiveBuses() {
        if (_uiState.value.selectedLines.isEmpty()) return
        viewModelScope.launch {
            fetchActiveBusesForAllLines()
        }
    }

    private suspend fun fetchActiveBusesForAllLines() {
        val currentLines = _uiState.value.selectedLines
        if (currentLines.isEmpty()) return

        _uiState.update { it.copy(isLoadingBuses = true, busesErrorMessage = null) }

        val results = try {
            coroutineScope {
                currentLines.map { line ->
                    async {
                        val res = runCatching { repository.getActiveBuses(line.codLinea) }
                            .getOrElse { Result.failure(it) }
                        line.codLinea to res
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            emptyList()
        }

        _uiState.update { state ->
            var updatedMap = state.activeLines
            results.forEach { (lineId, res) ->
                val lineData = updatedMap[lineId]
                if (lineData != null && res.isSuccess) {
                    val buses = res.getOrDefault(emptyList())
                    updatedMap = updatedMap + (lineId to lineData.copy(activeBuses = buses))
                }
            }

            val allBuses = updatedMap.values.flatMap { it.activeBuses }
            val allNodes = updatedMap.values.flatMap { it.routeNodes }
            val liveDetails = computeLiveDetails(allBuses, allNodes)

            state.copy(
                isLoadingBuses = false,
                activeLines = updatedMap,
                activeBuses = allBuses,
                busLiveDetails = liveDetails,
                busesErrorMessage = null
            )
        }
    }

    private fun computeLiveDetails(
        buses: List<BusPos>,
        allNodes: List<BusNode> = _uiState.value.routeNodes,
        now: Long = System.currentTimeMillis()
    ): Map<String, BusLiveDetails> {
        val stops = allNodes.filter { it.parada }
        val userLoc = _uiState.value.userLocation

        // Find the bus stop on this route closest to the USER using fast squared distance
        val userNearestStop = if (userLoc != null && stops.isNotEmpty()) {
            stops.minByOrNull {
                val dLat = userLoc.latitude - it.latitud
                val dLon = userLoc.longitude - it.longitud
                dLat * dLat + dLon * dLon
            }
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

            // 1. Distance between this bus and the closest bus stop to the user
            var userStopDist: Double? = null
            var userStopName: String? = null
            if (userNearestStop != null) {
                userStopDist = calculateDistanceMeters(bus.latitud, bus.longitud, userNearestStop.latitud, userNearestStop.longitud)
                userStopName = userNearestStop.descripcionParada?.takeIf { it.isNotBlank() } ?: userNearestStop.codigoParada
            }

            // 2. Distance to nearest stop of the bus itself (fallback) using fast search
            var busStopName: String? = null
            var busStopDist: Double? = null
            if (stops.isNotEmpty()) {
                val nearestToBus = stops.minByOrNull {
                    val dLat = bus.latitud - it.latitud
                    val dLon = bus.longitud - it.longitud
                    dLat * dLat + dLon * dLon
                }
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

        var isInitialFix = true
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                setUserLocationInternal(location)
                if (isInitialFix || _uiState.value.isLocatingUser) {
                    isInitialFix = false
                    _uiState.update { it.copy(isLocatingUser = false) }
                    onLocationReady?.invoke(location)
                }
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
        }
        locationListener = listener

        for (p in providers) {
            try {
                locationManager.requestLocationUpdates(
                    p,
                    2000L,
                    2f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (_: SecurityException) {}
        }

        // Poll user location for at least 5 minutes before giving up (300,000 ms)
        locationTimeoutJob?.cancel()
        locationTimeoutJob = viewModelScope.launch {
            delay(300_000L)
            stopLocationUpdates()
        }
    }

    private fun stopLocationUpdates() {
        _uiState.update { it.copy(isLocatingUser = false) }
        locationListener?.let {
            try { locationManager.removeUpdates(it) } catch (_: SecurityException) {}
            locationListener = null
        }
    }

    fun updateMapCamera(lat: Double, lon: Double, zoom: Double) {
        _uiState.update { it.copy(cameraState = MapCameraState(lat, lon, zoom)) }
    }

    fun onRouteBoundsFitted() {
        _uiState.update { it.copy(shouldFitRouteBounds = false) }
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
        locationTimeoutJob?.cancel()
        stopLocationUpdates()
    }
}
