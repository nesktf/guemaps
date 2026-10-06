package com.nesktf.guemaps.ui.map

import android.location.Location
import com.nesktf.guemaps.data.model.ActiveLineData
import com.nesktf.guemaps.data.model.BusCategory
import com.nesktf.guemaps.data.model.BusGroupNode
import com.nesktf.guemaps.data.model.BusLiveDetails
import com.nesktf.guemaps.data.model.BusNode
import com.nesktf.guemaps.data.model.BusPos
import com.nesktf.guemaps.data.model.BusPreset
import com.nesktf.guemaps.data.model.BusStopRecord
import com.nesktf.guemaps.data.model.FlatBusLine
import com.nesktf.guemaps.data.model.MapBusStop

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

sealed class AddStopResult {
    data class Added(val line: FlatBusLine) : AddStopResult()
    data class AlreadySelected(val line: FlatBusLine) : AddStopResult()
    object LimitReached : AddStopResult()
    object NotFound : AddStopResult()
}
