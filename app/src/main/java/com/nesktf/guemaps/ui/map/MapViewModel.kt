package com.nesktf.guemaps.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.model.BusCategory
import com.nesktf.guemaps.data.model.BusGroupNode
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
    val flatLines: List<FlatBusLine> = emptyList(),
    val filteredLines: List<FlatBusLine> = emptyList(),
    val selectedLine: FlatBusLine? = null,
    val isLoadingRoute: Boolean = false,
    val routeNodes: List<BusNode> = emptyList(),
    val isRouteOffline: Boolean = false,
    val isGroupsOffline: Boolean = false,
    val activeBuses: List<BusPos> = emptyList(),
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
    }

    private val repository: BusRepository
    private var busPollingJob: Job? = null

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        val database = GuemapsDatabase(application)
        val apiClient = SaetaApiClient()
        repository = BusRepository(apiClient, database)
        loadBusGroups()
    }

    fun loadBusGroups(forceNetwork: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingGroups = true, errorMessage = null) }
            val result = repository.getBusGroups(forceNetwork = forceNetwork)
            result.onSuccess { groupsResult ->
                val cats = groupsResult.rootGroup.extractCategories()
                // Default expand all subgroups
                val allSubgroupKeys = cats.flatMap { cat ->
                    cat.subgroups.map { "${cat.name}::${it.subgroupName}" }
                }.toSet()

                _uiState.update { state ->
                    val filtered = filterLines(groupsResult.flatLines, state.searchQuery)
                    state.copy(
                        isLoadingGroups = false,
                        busGroups = groupsResult.rootGroup,
                        categories = cats,
                        expandedSubgroups = if (state.expandedSubgroups.isEmpty()) allSubgroupKeys else state.expandedSubgroups,
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
            _uiState.update {
                it.copy(
                    isLoadingBuses = false,
                    activeBuses = buses,
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
    }
}
