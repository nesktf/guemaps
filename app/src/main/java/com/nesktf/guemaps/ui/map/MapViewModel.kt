package com.nesktf.guemaps.ui.map

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nesktf.guemaps.R
import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.model.ActiveLineData
import com.nesktf.guemaps.data.model.BusCategory
import com.nesktf.guemaps.data.model.BusGroupNode
import com.nesktf.guemaps.data.model.BusLiveDetails
import com.nesktf.guemaps.data.model.BusNode
import com.nesktf.guemaps.data.model.BusPos
import com.nesktf.guemaps.data.model.BusStopRecord
import com.nesktf.guemaps.data.model.FlatBusLine
import com.nesktf.guemaps.data.model.MapBusStop
import com.nesktf.guemaps.data.model.clusterBusStops
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nesktf.guemaps.data.model.BusPreset
import com.nesktf.guemaps.data.model.computeLineCodesHash
import java.util.UUID
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

data class MapCameraState(
    val latitude: Double = -24.7859,
    val longitude: Double = -65.4117,
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
    val presets: List<BusPreset> = emptyList(),
    val flatLines: List<FlatBusLine> = emptyList(),
    val filteredLines: List<FlatBusLine> = emptyList(),
    val filteredStops: List<Pair<BusStopRecord, FlatBusLine?>> = emptyList(),
    val isSyncingStops: Boolean = false,
    val syncProgressText: String? = null,
    val feedbackMessage: String? = null,
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
    val selectedReferenceStop: MapBusStop? = null,
    val stopToastMessage: String? = null,
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
        const val MAX_SELECTED_LINES = 5
        const val POLLING_INTERVAL_MS = 10_000L // 10 seconds polling interval
        const val SPEED_BUFFER_SIZE = 16
        private const val PREFS_NAME = "guemaps_prefs"
        private const val KEY_LAST_LINE_CODE = "last_line_code"
        private const val KEY_LAST_LINE_DESC = "last_line_desc"
        private const val KEY_LAST_LINE_PATH = "last_line_path"
        private const val KEY_SELECTED_LINES_JSON = "selected_lines_json"
        private const val KEY_PRESETS_JSON = "bus_presets_json"

        /**
         * Additional offset added to calculated bus arrival time in seconds (e.g. 120s = 2 minutes)
         * to compensate for real-world traffic, passenger boarding, and API delays.
         * Tweak this value as needed.
         */
        const val BUS_ARRIVAL_TIME_OFFSET_SECONDS = 120L
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val repository: BusRepository
    private var busPollingJob: Job? = null
    private var locationTimeoutJob: Job? = null
    private val busSpeedTracker = com.nesktf.guemaps.data.model.BusSpeedTracker(bufferSize = SPEED_BUFFER_SIZE)
    private val locationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var locationListener: LocationListener? = null
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectionJob: Job? = null

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        val database = GuemapsDatabase(application)
        val apiClient = SaetaApiClient()
        repository = BusRepository(apiClient, database)

        // Setup network connectivity callback for offline/online handling
        setupNetworkCallback()

        // Load presets
        loadPresets()

        // Restore saved selected lines on boot
        val savedLines = getSavedSelectedLines()
        if (savedLines.isNotEmpty()) {
            var colorIndex = 0
            val activeMap = mutableMapOf<String, ActiveLineData>()
            val palette = BUS_LINE_PALETTE.take(MAX_SELECTED_LINES)
            savedLines.forEach { line ->
                val color = palette[colorIndex % palette.size]
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

    fun clearAllLines() {
        busPollingJob?.cancel()
        saveSelectedLines(emptyList())
        _uiState.update {
            it.copy(
                selectedLines = emptyList(),
                activeLines = emptyMap(),
                selectedLine = null,
                routeNodes = emptyList(),
                activeBuses = emptyList(),
                busLiveDetails = emptyMap(),
                selectedBusInterno = null,
                errorMessage = null,
                isLoadingRoute = false
            )
        }
    }

    fun loadPresets() {
        viewModelScope.launch {
            val json = prefs.getString(KEY_PRESETS_JSON, null)
            val presetsList: List<BusPreset> = if (!json.isNullOrBlank()) {
                try {
                    val type = object : TypeToken<List<BusPreset>>() {}.type
                    Gson().fromJson(json, type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            _uiState.update { it.copy(presets = presetsList) }
        }
    }

    private fun savePresets(presets: List<BusPreset>) {
        val json = Gson().toJson(presets)
        prefs.edit().putString(KEY_PRESETS_JSON, json).apply()
        _uiState.update { it.copy(presets = presets) }
    }

    fun saveCurrentPreset(name: String): Result<BusPreset> {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return Result.failure(IllegalArgumentException("El nombre no puede estar vacío"))
        }
        val currentLines = _uiState.value.selectedLines
        if (currentLines.isEmpty()) {
            return Result.failure(IllegalStateException("No hay líneas seleccionadas para guardar"))
        }

        val canonicalHash = computeLineCodesHash(currentLines)
        val existingPreset = _uiState.value.presets.find { it.lineCodesHash == canonicalHash }
        if (existingPreset != null) {
            return Result.failure(IllegalStateException("Ya existe un ajuste guardado con estas líneas: \"${existingPreset.name}\""))
        }

        val newPreset = BusPreset(
            id = UUID.randomUUID().toString(),
            name = trimmedName,
            lineCodesHash = canonicalHash,
            lines = currentLines,
            createdAt = System.currentTimeMillis()
        )

        val updated = _uiState.value.presets + newPreset
        savePresets(updated)
        return Result.success(newPreset)
    }

    fun deletePreset(presetId: String) {
        val updated = _uiState.value.presets.filter { it.id != presetId }
        savePresets(updated)
    }

    fun loadPreset(preset: BusPreset) {
        val linesToLoad = preset.lines.take(MAX_SELECTED_LINES)
        busPollingJob?.cancel()
        saveSelectedLines(linesToLoad)

        val palette = BUS_LINE_PALETTE.take(MAX_SELECTED_LINES)
        val newActiveMap = mutableMapOf<String, ActiveLineData>()
        linesToLoad.forEachIndexed { index, line ->
            val color = palette[index % palette.size]
            newActiveMap[line.codLinea] = ActiveLineData(
                line = line,
                colorHex = color,
                isLoadingRoute = true
            )
        }

        _uiState.update {
            it.copy(
                selectedLines = linesToLoad,
                activeLines = newActiveMap,
                selectedLine = linesToLoad.firstOrNull(),
                routeNodes = emptyList(),
                activeBuses = emptyList(),
                busLiveDetails = emptyMap(),
                errorMessage = null,
                shouldFitRouteBounds = true,
                isLinePickerOpen = false
            )
        }

        busSpeedTracker.clear()

        linesToLoad.forEach { loadRouteForLine(it.codLinea) }
        restartActiveBusesPolling()
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
        val palette = BUS_LINE_PALETTE.take(MAX_SELECTED_LINES)
        return palette.firstOrNull { it !in usedColors }
            ?: palette[currentActiveLines.size % palette.size]
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
                if (list.isNotEmpty()) return list.take(MAX_SELECTED_LINES)
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
                triggerInitialRoutesSync(groupsResult.flatLines)
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

            // If selected bus belonged to removed line, clear it
            val remainingInternos = allBuses.map { it.interno }.toSet()
            val updatedSelectedBus = state.selectedBusInterno?.takeIf { remainingInternos.contains(it) }

            busSpeedTracker.pruneTrackersExcept(remainingInternos)

            // If selected reference stop belonged only to removed line, clear it
            val updatedRefStop = state.selectedReferenceStop?.let { refStop ->
                if (refStop.lineCodes.isEmpty() || refStop.lineCodes.any { lineCode -> updatedLines.any { it.codLinea == lineCode } }) {
                    refStop
                } else {
                    null
                }
            }

            _uiState.update {
                it.copy(
                    selectedLines = updatedLines,
                    activeLines = updatedActiveMap,
                    selectedLine = updatedLines.firstOrNull(),
                    routeNodes = allNodes,
                    activeBuses = allBuses,
                    selectedBusInterno = updatedSelectedBus,
                    selectedReferenceStop = updatedRefStop,
                    errorMessage = null
                )
            }
            // Auto-select nearest stop from remaining lines if ref stop was cleared and user location exists
            val userLoc = _uiState.value.userLocation
            if (_uiState.value.selectedReferenceStop == null && userLoc != null) {
                updateNearestReferenceStop(userLoc)
            }
            recalculateBusLiveDetails()
            if (updatedLines.isNotEmpty()) {
                restartActiveBusesPolling()
            } else {
                busPollingJob?.cancel()
            }
        } else {
            if (state.selectedLines.size >= MAX_SELECTED_LINES) {
                _uiState.update { it.copy(errorMessage = "Máximo $MAX_SELECTED_LINES líneas permitidas al mismo tiempo") }
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
                val userLoc = _uiState.value.userLocation
                if (_uiState.value.selectedReferenceStop == null && userLoc != null) {
                    updateNearestReferenceStop(userLoc)
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

    fun isNetworkAvailable(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isOfflineMode(): Boolean {
        return _uiState.value.isGroupsOffline || _uiState.value.isRouteOffline || !isNetworkAvailable()
    }

    private fun setupNetworkCallback() {
        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isOfflineMode()) {
                        viewModelScope.launch {
                            attemptReconnection(isManual = false)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    busPollingJob?.cancel()
                    _uiState.update { it.copy(isLoadingBuses = false) }
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: Exception) {}
    }

    suspend fun attemptReconnection(isManual: Boolean) {
        if (reconnectionJob?.isActive == true) return
        val job = viewModelScope.launch {
            if (!isNetworkAvailable()) {
                if (isManual) {
                    val msg = getApplication<Application>().getString(R.string.map_no_active_network)
                    showFeedbackMessage(msg)
                    showStopToast(msg)
                }
                return@launch
            }

            _uiState.update { it.copy(isLoadingBuses = true) }

            val groupsResult = runCatching { repository.getBusGroups(forceNetwork = true) }.getOrNull()
            val groupsSuccess = groupsResult != null && groupsResult.isSuccess && !groupsResult.getOrThrow().isFromCache

            if (!groupsSuccess) {
                _uiState.update { it.copy(isLoadingBuses = false) }
                if (isManual) {
                    val msg = getApplication<Application>().getString(R.string.map_no_active_network)
                    showFeedbackMessage(msg)
                    showStopToast(msg)
                }
                return@launch
            }

            val gRes = groupsResult.getOrThrow()
            val cats = gRes.rootGroup.extractCategories()

            _uiState.update { state ->
                val filtered = filterLines(gRes.flatLines, state.searchQuery)
                state.copy(
                    busGroups = gRes.rootGroup,
                    categories = cats,
                    flatLines = gRes.flatLines,
                    filteredLines = filtered,
                    isGroupsOffline = false
                )
            }

            // Re-fetch routes for currently selected active lines with forceNetwork = true
            val currentLines = _uiState.value.selectedLines
            for (line in currentLines) {
                val routeRes = runCatching { repository.getBusRoute(line.codLinea, forceNetwork = true) }.getOrNull()
                if (routeRes != null && routeRes.isSuccess && !routeRes.getOrThrow().isFromCache) {
                    val nodes = routeRes.getOrThrow().nodes
                    _uiState.update { state ->
                        val lineData = state.activeLines[line.codLinea]
                        if (lineData != null) {
                            val updated = lineData.copy(routeNodes = nodes, isRouteOffline = false)
                            state.copy(activeLines = state.activeLines + (line.codLinea to updated))
                        } else state
                    }
                }
            }

            _uiState.update { state ->
                val allNodes = state.activeLines.values.flatMap { it.routeNodes }
                val anyRouteOffline = state.activeLines.values.any { it.isRouteOffline }
                state.copy(
                    routeNodes = if (allNodes.isNotEmpty()) allNodes else state.routeNodes,
                    isRouteOffline = anyRouteOffline,
                    isLoadingBuses = false
                )
            }

            val netFoundMsg = getApplication<Application>().getString(R.string.map_network_found)
            showFeedbackMessage(netFoundMsg)
            showStopToast(netFoundMsg)

            // Re-enter online mode: restart active buses polling and immediately fetch active buses
            restartActiveBusesPolling()
            fetchActiveBusesForAllLines()
        }
        reconnectionJob = job
        job.join()
    }

    private fun restartActiveBusesPolling() {
        busPollingJob?.cancel()
        val lines = _uiState.value.selectedLines
        if (lines.isEmpty()) return

        if (isOfflineMode()) {
            _uiState.update { it.copy(isLoadingBuses = false) }
            return
        }

        busPollingJob = viewModelScope.launch {
            while (isActive) {
                if (isOfflineMode()) {
                    _uiState.update { it.copy(isLoadingBuses = false) }
                    break
                }
                fetchActiveBusesForAllLines()
                delay(10_000) // Poll every 10 seconds
            }
        }
    }

    fun refreshActiveBuses() {
        if (_uiState.value.selectedLines.isEmpty()) return
        if (isOfflineMode()) {
            viewModelScope.launch {
                attemptReconnection(isManual = true)
            }
        } else {
            restartActiveBusesPolling()
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
        val selectedRefStop = _uiState.value.selectedReferenceStop

        // If user has a selected reference stop, use that! Otherwise find the bus stop on this route closest to the USER
        val (refStopLat, refStopLon, refStopName) = if (selectedRefStop != null) {
            Triple(selectedRefStop.latitude, selectedRefStop.longitude, selectedRefStop.name.takeIf { it.isNotBlank() } ?: selectedRefStop.code)
        } else {
            val userNearestStop = if (userLoc != null && stops.isNotEmpty()) {
                stops.minByOrNull {
                    val dLat = userLoc.latitude - it.latitud
                    val dLon = userLoc.longitude - it.longitud
                    dLat * dLat + dLon * dLon
                }
            } else null
            if (userNearestStop != null) {
                Triple(userNearestStop.latitud, userNearestStop.longitud, userNearestStop.descripcionParada?.takeIf { it.isNotBlank() } ?: userNearestStop.codigoParada)
            } else {
                Triple(null, null, null)
            }
        }

        val detailsMap = mutableMapOf<String, BusLiveDetails>()

        buses.forEach { bus ->
            val evaluation = busSpeedTracker.processBusPosition(bus, now)
            val speed = evaluation.instantSpeedKmh
            val avgSpeed = evaluation.averageSpeedKmh

            // 1. Distance between this bus and the reference stop (selected or closest to user)
            var userStopDist: Double? = null
            var userStopName: String? = null
            if (refStopLat != null && refStopLon != null) {
                userStopDist = calculateDistanceMeters(bus.latitud, bus.longitud, refStopLat, refStopLon)
                userStopName = refStopName
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

            // 3. Estimated waiting time to reference stop
            var estimatedSeconds: Long? = null
            var estimatedArrivalEpoch: Long? = null
            if (userStopDist != null) {
                if (userStopDist <= 25.0) {
                    estimatedSeconds = 0L
                    estimatedArrivalEpoch = now
                } else {
                    val effectiveSpeed = avgSpeed ?: speed
                    if (effectiveSpeed != null && effectiveSpeed >= 1.0) {
                        val speedMps = effectiveSpeed / 3.6
                        val rawSecs = Math.round(userStopDist / speedMps)
                        val totalSecs = rawSecs + BUS_ARRIVAL_TIME_OFFSET_SECONDS
                        estimatedSeconds = totalSecs
                        estimatedArrivalEpoch = now + (totalSecs * 1000L)
                    }
                }
            }

            detailsMap[bus.interno] = BusLiveDetails(
                bus = bus,
                speedKmh = speed,
                averageSpeedKmh = avgSpeed,
                userNearestStopName = userStopName,
                distanceToUserStopMeters = userStopDist,
                nearestStopName = busStopName ?: bus.proximaParada,
                distanceToNearestStopMeters = busStopDist,
                estimatedSecondsRemaining = estimatedSeconds,
                estimatedArrivalEpochMs = estimatedArrivalEpoch
            )
        }
        busSpeedTracker.pruneTrackersExcept(buses.map { it.interno }.toSet())

        return detailsMap
    }

    private fun recalculateBusLiveDetails() {
        val current = _uiState.value.activeBuses
        if (current.isNotEmpty()) {
            val details = computeLiveDetails(current)
            _uiState.update { it.copy(busLiveDetails = details) }
        }
    }

    private fun updateNearestReferenceStop(userLoc: Location) {
        val activeLinesList = _uiState.value.activeLines.values.toList()
        val allStops = clusterBusStops(activeLinesList, _uiState.value.routeNodes)
        if (allStops.isEmpty()) return

        val nearest = allStops.minByOrNull { stop ->
            val dLat = (userLoc.latitude - stop.latitude) * 111000.0
            val dLon = (userLoc.longitude - stop.longitude) * 100700.0
            dLat * dLat + dLon * dLon
        }
        if (nearest != null) {
            _uiState.update { it.copy(selectedReferenceStop = nearest) }
        }
    }

    fun selectNearestStopToUser() {
        val userLoc = _uiState.value.userLocation ?: return
        val activeLinesList = _uiState.value.activeLines.values.toList()
        val allStops = clusterBusStops(activeLinesList, _uiState.value.routeNodes)
        if (allStops.isEmpty()) {
            showStopToast("No hay paradas disponibles")
            return
        }

        val nearest = allStops.minByOrNull { stop ->
            val dLat = (userLoc.latitude - stop.latitude) * 111000.0
            val dLon = (userLoc.longitude - stop.longitude) * 100700.0
            dLat * dLat + dLon * dLon
        }
        if (nearest != null) {
            selectReferenceStop(nearest)
            val stopName = nearest.name.takeIf { it.isNotBlank() } ?: "Parada de colectivo"
            val linesText = if (nearest.lineNames.isNotEmpty()) {
                if (nearest.lineNames.size == 1) "Línea ${nearest.lineNames.first()}"
                else "Líneas: ${nearest.lineNames.joinToString(", ")}"
            } else ""
            val msg = if (linesText.isNotBlank()) "$stopName\n$linesText" else stopName
            showStopToast(msg)
        }
    }

    fun isLocationPermissionGranted(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return hasFine || hasCoarse
    }

    fun isLocationProviderEnabled(): Boolean {
        return try {
            LocationManagerCompat.isLocationEnabled(locationManager)
        } catch (_: Exception) {
            val enabledProviders = try { locationManager.getProviders(true) } catch (_: Exception) { emptyList() }
            enabledProviders.isNotEmpty()
        }
    }

    fun requestUserLocation(onLocationReady: ((Location) -> Unit)? = null) {
        if (!isLocationPermissionGranted() || !isLocationProviderEnabled()) {
            return
        }

        _uiState.update { it.copy(isLocatingUser = true) }

        // Check if last known location is available across all providers
        val allProviders = try { locationManager.allProviders } catch (_: Exception) { emptyList() }

        var bestLast: Location? = null
        for (p in allProviders) {
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

        val enabledProviders = try { locationManager.getProviders(true) } catch (_: Exception) { emptyList() }
        for (p in enabledProviders) {
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

        // Poll user location with a 30-second timeout before giving up
        locationTimeoutJob?.cancel()
        locationTimeoutJob = viewModelScope.launch {
            delay(30_000L)
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
        if (_uiState.value.selectedReferenceStop == null) {
            updateNearestReferenceStop(location)
        }
        recalculateBusLiveDetails()
    }

    fun isBusServingStop(interno: String, stop: MapBusStop): Boolean {
        val lineData = _uiState.value.activeLines.values.find { ld ->
            ld.activeBuses.any { it.interno == interno }
        } ?: return false

        if (stop.lineCodes.contains(lineData.line.codLinea)) return true

        return lineData.routeNodes.any { node ->
            node.parada && (
                (node.codigoParada != null && stop.code != null && node.codigoParada.equals(stop.code, ignoreCase = true)) ||
                calculateDistanceMeters(node.latitud, node.longitud, stop.latitude, stop.longitude) <= 25.0
            )
        }
    }

    fun selectBusForFloatingCard(interno: String) {
        val currentStop = _uiState.value.selectedReferenceStop
        if (currentStop != null && _uiState.value.selectedBusInterno != interno) {
            if (!isBusServingStop(interno, currentStop)) {
                showStopToast(getApplication<Application>().getString(R.string.map_bus_does_not_pass_stop))
                return
            }
        }
        _uiState.update { state ->
            val newSelection = if (state.selectedBusInterno == interno) null else interno
            state.copy(selectedBusInterno = newSelection)
        }
    }

    fun clearSelectedBus() {
        _uiState.update { it.copy(selectedBusInterno = null) }
    }

    fun selectReferenceStop(stop: MapBusStop?) {
        var shouldDeselectBus = false
        val currentBus = _uiState.value.selectedBusInterno
        if (stop != null && currentBus != null) {
            if (!isBusServingStop(currentBus, stop)) {
                shouldDeselectBus = true
            }
        }

        _uiState.update { state ->
            state.copy(
                selectedReferenceStop = stop,
                selectedBusInterno = if (shouldDeselectBus) null else state.selectedBusInterno
            )
        }
        if (shouldDeselectBus) {
            showStopToast(getApplication<Application>().getString(R.string.map_bus_does_not_pass_stop))
        }
        recalculateBusLiveDetails()
    }

    private var stopToastJob: Job? = null

    fun showStopToast(message: String) {
        stopToastJob?.cancel()
        _uiState.update { it.copy(stopToastMessage = message) }
        stopToastJob = viewModelScope.launch {
            delay(3500L)
            _uiState.update { it.copy(stopToastMessage = null) }
        }
    }

    fun clearStopToast() {
        stopToastJob?.cancel()
        _uiState.update { it.copy(stopToastMessage = null) }
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
        val filteredL = filterLines(_uiState.value.flatLines, query)
        val stops = if (query.trim().length >= 2) {
            val stopRecords = repository.searchBusStops(query.trim())
            val lineMap = _uiState.value.flatLines.associateBy { it.codLinea }
            stopRecords.map { stop -> Pair(stop, lineMap[stop.lineId]) }
        } else {
            emptyList()
        }
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredLines = filteredL,
                filteredStops = stops
            )
        }
    }

    sealed class AddStopResult {
        data class Added(val line: FlatBusLine) : AddStopResult()
        data class AlreadySelected(val line: FlatBusLine) : AddStopResult()
        object LimitReached : AddStopResult()
        object NotFound : AddStopResult()
    }

    fun addBusLineFromStop(stop: BusStopRecord): AddStopResult {
        val state = _uiState.value
        val line = state.flatLines.find { it.codLinea == stop.lineId }
            ?: return AddStopResult.NotFound

        val isAlreadySelected = state.selectedLines.any { it.codLinea == line.codLinea }
        if (isAlreadySelected) {
            val msg = "La Línea ${line.nombreCorto} ya está en el mapa"
            _uiState.update { it.copy(feedbackMessage = msg, shouldFitRouteBounds = false) }
            return AddStopResult.AlreadySelected(line)
        }

        if (state.selectedLines.size >= MAX_SELECTED_LINES) {
            val msg = "Límite alcanzado (máximo $MAX_SELECTED_LINES colectivos)"
            _uiState.update { it.copy(feedbackMessage = msg) }
            return AddStopResult.LimitReached
        }

        // Add line
        toggleLineSelection(line)
        val msg = "Línea ${line.nombreCorto} agregada"
        _uiState.update { it.copy(feedbackMessage = msg, shouldFitRouteBounds = false) }
        return AddStopResult.Added(line)
    }

    fun showFeedbackMessage(message: String) {
        _uiState.update { it.copy(feedbackMessage = message) }
    }

    fun clearFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    private var syncJob: Job? = null

    private fun triggerInitialRoutesSync(lines: List<FlatBusLine>) {
        if (syncJob?.isActive == true || lines.isEmpty()) return
        val cachedCount = repository.getCachedRoutesCount()
        if (cachedCount >= lines.size) return // All routes already cached and stops indexed

        syncJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSyncingStops = true,
                    syncProgressText = "Sincronizando paradas offline ($cachedCount/${lines.size})..."
                )
            }
            repository.syncAllBusRoutes(lines) { current, total ->
                _uiState.update {
                    it.copy(
                        syncProgressText = "Sincronizando paradas offline ($current/$total)..."
                    )
                }
            }
            _uiState.update {
                it.copy(
                    isSyncingStops = false,
                    syncProgressText = null
                )
            }
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
        syncJob?.cancel()
        stopToastJob?.cancel()
        reconnectionJob?.cancel()
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
            networkCallback = null
        }
        stopLocationUpdates()
    }
}
