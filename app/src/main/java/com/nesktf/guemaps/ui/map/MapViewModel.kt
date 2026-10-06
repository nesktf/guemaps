package com.nesktf.guemaps.ui.map

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
import com.nesktf.guemaps.util.LocationTracker
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
import com.nesktf.guemaps.data.local.MapPreferences
import com.nesktf.guemaps.data.model.BusPreset
import com.nesktf.guemaps.data.model.calculateDistanceMeters
import com.nesktf.guemaps.data.model.computeBusLiveDetails
import com.nesktf.guemaps.data.model.computeLineCodesHash
import com.nesktf.guemaps.util.PresetShareUtils
import com.nesktf.guemaps.util.SharedPresetPayload
import android.net.Uri
import java.util.UUID

class MapViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val MAX_SELECTED_LINES = 5
        const val POLLING_INTERVAL_MS = 10_000L // 10 seconds polling interval
        const val SPEED_BUFFER_SIZE = 16

        /**
         * Additional offset added to calculated bus arrival time in seconds (e.g. 120s = 2 minutes)
         * to compensate for real-world traffic, passenger boarding, and API delays.
         * Tweak this value as needed.
         */
        const val BUS_ARRIVAL_TIME_OFFSET_SECONDS = 120L
    }

    private val mapPreferences = MapPreferences(application)
    private val locationTracker = LocationTracker(application)
    private val repository: BusRepository
    private var busPollingJob: Job? = null
    private val busSpeedTracker = com.nesktf.guemaps.data.model.BusSpeedTracker(bufferSize = SPEED_BUFFER_SIZE)
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectionJob: Job? = null

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        repository = (application as? GuemapsApplication)?.busRepository
            ?: BusRepository(SaetaApiClient(), GuemapsDatabase(application))

        // Setup network connectivity callback for offline/online handling
        setupNetworkCallback()

        // Load presets
        loadPresets()

        // Restore saved selected lines on boot
        val savedLines = getSavedSelectedLines()
        if (savedLines.isNotEmpty()) {
            val cachedCatalog = repository.getCachedFlatLines().associateBy { it.codLinea.trim() }
            val enrichedSavedLines = if (cachedCatalog.isNotEmpty()) {
                savedLines.map { line ->
                    cachedCatalog[line.codLinea.trim()]?.let {
                        line.copy(groupPath = it.groupPath, descripcion = it.descripcion)
                    } ?: line
                }
            } else {
                savedLines
            }

            var colorIndex = 0
            val activeMap = mutableMapOf<String, ActiveLineData>()
            val palette = BUS_LINE_PALETTE.take(MAX_SELECTED_LINES)
            enrichedSavedLines.forEach { line ->
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
                    selectedLines = enrichedSavedLines,
                    activeLines = activeMap,
                    selectedLine = enrichedSavedLines.firstOrNull(),
                    isLoadingRoute = true,
                    shouldFitRouteBounds = true
                )
            }
            enrichedSavedLines.forEach { line ->
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
        val presetsList = mapPreferences.loadPresets()
        _uiState.update { it.copy(presets = presetsList) }
    }

    private fun savePresets(presets: List<BusPreset>) {
        mapPreferences.savePresets(presets)
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

    fun enrichFlatLines(lines: List<FlatBusLine>): List<FlatBusLine> {
        val catalog = if (_uiState.value.flatLines.isNotEmpty()) {
            _uiState.value.flatLines
        } else {
            repository.getCachedFlatLines()
        }
        if (catalog.isEmpty()) return lines

        val catalogMap = catalog.associateBy { it.codLinea.trim() }
        return lines.map { line ->
            val matched = catalogMap[line.codLinea.trim()]
            if (matched != null) {
                line.copy(
                    groupPath = matched.groupPath,
                    descripcion = matched.descripcion
                )
            } else {
                line
            }
        }
    }

    fun enrichPayloadWithCatalog(payload: SharedPresetPayload): SharedPresetPayload {
        val enrichedLines = enrichFlatLines(payload.lines)
        return payload.copy(lines = enrichedLines)
    }

    fun loadPreset(preset: BusPreset) {
        val enrichedLines = enrichFlatLines(preset.lines)
        val linesToLoad = enrichedLines.take(MAX_SELECTED_LINES)
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

    fun loadPresetPayload(payload: SharedPresetPayload) {
        val enrichedPayload = enrichPayloadWithCatalog(payload)
        val linesToLoad = enrichedPayload.lines.take(MAX_SELECTED_LINES)
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

        val refStop = enrichedPayload.stop?.let { s ->
            MapBusStop(
                id = s.stopCode.ifEmpty { "stop_${s.latitude}_${s.longitude}" },
                name = s.stopName,
                code = s.stopCode.ifEmpty { null },
                latitude = s.latitude,
                longitude = s.longitude,
                lineCodes = if (s.lineId.isNotEmpty()) listOf(s.lineId) else emptyList()
            )
        }

        _uiState.update {
            it.copy(
                selectedLines = linesToLoad,
                activeLines = newActiveMap,
                selectedLine = linesToLoad.firstOrNull(),
                selectedReferenceStop = refStop ?: it.selectedReferenceStop,
                routeNodes = emptyList(),
                activeBuses = emptyList(),
                busLiveDetails = emptyMap(),
                errorMessage = null,
                shouldFitRouteBounds = true,
                isLinePickerOpen = false,
                incomingPresetForConfirmation = null,
                activePresetForSharing = null,
                isQrScannerOpen = false
            )
        }

        busSpeedTracker.clear()

        linesToLoad.forEach { loadRouteForLine(it.codLinea) }
        restartActiveBusesPolling()
        showFeedbackMessage(getApplication<Application>().getString(R.string.line_picker_preset_loaded, payload.name))
    }

    fun openShareCurrentActiveLines() {
        val currentLines = _uiState.value.selectedLines
        if (currentLines.isEmpty()) return

        val refStop = _uiState.value.selectedReferenceStop?.let { s ->
            BusStopRecord(
                lineId = s.lineCodes.firstOrNull() ?: currentLines.firstOrNull()?.codLinea ?: "",
                stopCode = s.code ?: s.id,
                stopName = s.name,
                latitude = s.latitude,
                longitude = s.longitude
            )
        }

        val defaultName = getApplication<Application>().getString(R.string.share_preset_default_name)
        val payload = SharedPresetPayload(
            name = defaultName,
            lines = currentLines,
            stop = refStop
        )
        _uiState.update { it.copy(activePresetForSharing = payload, isLinePickerOpen = false) }
    }

    fun openShareStoredPreset(preset: BusPreset) {
        val payload = SharedPresetPayload(
            name = preset.name,
            lines = preset.lines,
            stop = _uiState.value.selectedReferenceStop?.let { s ->
                BusStopRecord(
                    lineId = s.lineCodes.firstOrNull() ?: preset.lines.firstOrNull()?.codLinea ?: "",
                    stopCode = s.code ?: s.id,
                    stopName = s.name,
                    latitude = s.latitude,
                    longitude = s.longitude
                )
            }
        )
        _uiState.update { it.copy(activePresetForSharing = payload, isLinePickerOpen = false) }
    }

    fun closeSharePreset() {
        _uiState.update { it.copy(activePresetForSharing = null) }
    }

    fun setQrScannerOpen(open: Boolean) {
        _uiState.update { it.copy(isQrScannerOpen = open, isLinePickerOpen = false) }
    }

    fun handleScannedQr(content: String) {
        val rawPayload = PresetShareUtils.decodeFromUri(content)
        if (rawPayload != null) {
            val payload = enrichPayloadWithCatalog(rawPayload)
            _uiState.update { it.copy(isQrScannerOpen = false) }
            if (_uiState.value.selectedLines.isEmpty()) {
                loadPresetPayload(payload)
            } else {
                _uiState.update { it.copy(incomingPresetForConfirmation = payload) }
            }
        } else {
            showFeedbackMessage(getApplication<Application>().getString(R.string.scanner_invalid_qr))
        }
    }

    fun handleIncomingUri(uri: Uri) {
        val rawPayload = PresetShareUtils.decodeFromUri(uri)
        if (rawPayload != null) {
            val payload = enrichPayloadWithCatalog(rawPayload)
            if (_uiState.value.selectedLines.isEmpty()) {
                loadPresetPayload(payload)
            } else {
                _uiState.update { it.copy(incomingPresetForConfirmation = payload) }
            }
        } else {
            showFeedbackMessage(getApplication<Application>().getString(R.string.incoming_preset_error_invalid))
        }
    }

    fun confirmLoadIncomingPreset() {
        val payload = _uiState.value.incomingPresetForConfirmation ?: return
        loadPresetPayload(payload)
    }

    fun dismissIncomingPresetConfirmation() {
        _uiState.update { it.copy(incomingPresetForConfirmation = null) }
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
        mapPreferences.saveSelectedLines(lines)
    }

    private fun getSavedSelectedLines(): List<FlatBusLine> {
        return mapPreferences.getSavedSelectedLines()
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
                    val catalogMap = groupsResult.flatLines.associateBy { it.codLinea.trim() }

                    val updatedSelectedLines = state.selectedLines.map { line ->
                        catalogMap[line.codLinea.trim()]?.let { matched ->
                            line.copy(groupPath = matched.groupPath, descripcion = matched.descripcion)
                        } ?: line
                    }

                    val updatedActiveLines = state.activeLines.mapValues { (code, activeData) ->
                        val matched = catalogMap[code.trim()]
                        if (matched != null) {
                            activeData.copy(
                                line = activeData.line.copy(
                                    groupPath = matched.groupPath,
                                    descripcion = matched.descripcion
                                )
                            )
                        } else {
                            activeData
                        }
                    }

                    val updatedIncomingPreset = state.incomingPresetForConfirmation?.let { incoming ->
                        val enrichedIncomingLines = incoming.lines.map { line ->
                            catalogMap[line.codLinea.trim()]?.let {
                                line.copy(groupPath = it.groupPath, descripcion = it.descripcion)
                            } ?: line
                        }
                        incoming.copy(lines = enrichedIncomingLines)
                    }

                    state.copy(
                        isLoadingGroups = false,
                        busGroups = groupsResult.rootGroup,
                        categories = cats,
                        expandedSubgroups = if (state.expandedSubgroups.isEmpty()) bootSubgroups else state.expandedSubgroups,
                        flatLines = groupsResult.flatLines,
                        filteredLines = filtered,
                        selectedLines = updatedSelectedLines,
                        activeLines = updatedActiveLines,
                        incomingPresetForConfirmation = updatedIncomingPreset,
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
        return computeBusLiveDetails(
            buses = buses,
            allNodes = allNodes,
            speedTracker = busSpeedTracker,
            userLocation = _uiState.value.userLocation,
            selectedReferenceStop = _uiState.value.selectedReferenceStop,
            now = now,
            arrivalTimeOffsetSeconds = BUS_ARRIVAL_TIME_OFFSET_SECONDS
        )
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

    fun isLocationPermissionGranted(): Boolean = locationTracker.isPermissionGranted()

    fun isLocationProviderEnabled(): Boolean = locationTracker.isProviderEnabled()

    fun requestUserLocation(onLocationReady: ((Location) -> Unit)? = null) {
        locationTracker.requestLocationUpdates(
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
