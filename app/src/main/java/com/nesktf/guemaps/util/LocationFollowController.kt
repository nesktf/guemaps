package com.nesktf.guemaps.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CameraFocusRequest(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val zoom: Double? = null
)

data class LocationFollowState(
    val isFollowing: Boolean = false,
    val focusRequest: CameraFocusRequest? = null
)

class LocationFollowController(
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {
    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
    }

    private val _state = MutableStateFlow(LocationFollowState())
    val state: StateFlow<LocationFollowState> = _state.asStateFlow()

    private var idleTimerJob: Job? = null
    private var nextRequestId: Long = 0L
    private var lastLocation: Pair<Double, Double>? = null
    private var lastEmittedLocation: Pair<Double, Double>? = null
    private var pendingDefaultZoom: Double? = null

    init {
        startIdleTimer()
    }

    fun onLocationUpdated(latitude: Double, longitude: Double) {
        val current = latitude to longitude
        lastLocation = current
        if (!_state.value.isFollowing) return

        val zoom = pendingDefaultZoom
        pendingDefaultZoom = null

        if (zoom == null && lastEmittedLocation == current) return
        emitFocusRequest(latitude, longitude, zoom)
    }

    fun focusNow(defaultZoom: Double) {
        idleTimerJob?.cancel()
        idleTimerJob = null

        val loc = lastLocation
        if (loc != null) {
            pendingDefaultZoom = null
            _state.update { it.copy(isFollowing = true) }
            emitFocusRequest(loc.first, loc.second, defaultZoom)
        } else {
            pendingDefaultZoom = defaultZoom
            _state.update { it.copy(isFollowing = true) }
        }
    }

    fun onUserCameraInteraction() {
        pendingDefaultZoom = null
        lastEmittedLocation = null
        _state.update { it.copy(isFollowing = false) }
        startIdleTimer()
    }

    fun onZoomButtonPressed() {
        if (!_state.value.isFollowing) {
            startIdleTimer()
        }
    }

    fun consumeFocusRequest(requestId: Long) {
        _state.update { current ->
            if (current.focusRequest?.id == requestId) {
                current.copy(focusRequest = null)
            } else {
                current
            }
        }
    }

    fun cancel() {
        idleTimerJob?.cancel()
        idleTimerJob = null
    }

    private fun startIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = scope.launch {
            delayFn(idleTimeoutMs)
            pendingDefaultZoom = null
            _state.update { it.copy(isFollowing = true) }
            val loc = lastLocation
            if (loc != null) {
                emitFocusRequest(loc.first, loc.second, zoom = null)
            }
        }
    }

    private fun emitFocusRequest(latitude: Double, longitude: Double, zoom: Double?) {
        nextRequestId++
        lastEmittedLocation = latitude to longitude
        val request = CameraFocusRequest(
            id = nextRequestId,
            latitude = latitude,
            longitude = longitude,
            zoom = zoom
        )
        _state.update { it.copy(focusRequest = request) }
    }
}
