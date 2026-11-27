package com.nesktf.guemaps

import com.nesktf.guemaps.util.LocationFollowController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFollowControllerTest {

    private class ManualClock {
        private var currentGate: CompletableDeferred<Unit>? = null
        var delayStarts = 0
            private set

        suspend fun awaitDelay(@Suppress("UNUSED_PARAMETER") ms: Long) {
            val gate = CompletableDeferred<Unit>()
            currentGate = gate
            delayStarts++
            gate.await()
        }

        fun fireTimeout() {
            currentGate?.complete(Unit)
        }
    }

    private fun withController(block: suspend (LocationFollowController, ManualClock) -> Unit) = runBlocking {
        val clock = ManualClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = LocationFollowController(
            scope = scope,
            idleTimeoutMs = 120_000L,
            delayFn = { clock.awaitDelay(it) }
        )
        try {
            block(controller, clock)
        } finally {
            controller.cancel()
            scope.cancel()
        }
    }

    @Test
    fun startsInIdleCountdownAndIgnoresLocationUpdatesUntilTimeout() = withController { controller, clock ->
        assertFalse(controller.state.value.isFollowing)
        assertNull(controller.state.value.focusRequest)
        assertEquals(1, clock.delayStarts)

        controller.onLocationUpdated(-24.78, -65.41)
        assertFalse(controller.state.value.isFollowing)
        assertNull(controller.state.value.focusRequest)

        clock.fireTimeout()

        assertTrue(controller.state.value.isFollowing)
        val req = controller.state.value.focusRequest
        assertNotNull(req)
        assertEquals(-24.78, req!!.latitude, 1e-6)
        assertEquals(-65.41, req.longitude, 1e-6)
        assertNull(req.zoom)
    }

    @Test
    fun userInteractionPausesFollowAndResetsIdleTimer() = withController { controller, clock ->
        controller.onLocationUpdated(-24.78, -65.41)
        controller.focusNow(defaultZoom = 17.0)
        assertTrue(controller.state.value.isFollowing)

        controller.onUserCameraInteraction()
        assertFalse(controller.state.value.isFollowing)
        assertEquals(2, clock.delayStarts)

        controller.onUserCameraInteraction()
        assertEquals(3, clock.delayStarts)

        clock.fireTimeout()
        assertTrue(controller.state.value.isFollowing)
        assertNull(controller.state.value.focusRequest?.zoom)
    }

    @Test
    fun focusNowUsesDefaultZoomAndSubsequentUpdatesPreserveZoom() = withController { controller, _ ->
        controller.onLocationUpdated(-24.78, -65.41)
        controller.focusNow(defaultZoom = 17.0)

        val first = controller.state.value.focusRequest
        assertNotNull(first)
        assertEquals(17.0, first!!.zoom!!, 1e-6)
        controller.consumeFocusRequest(first.id)
        assertNull(controller.state.value.focusRequest)

        // Identical coordinates should not emit a duplicate pan request.
        controller.onLocationUpdated(-24.78, -65.41)
        assertNull(controller.state.value.focusRequest)

        controller.onLocationUpdated(-24.79, -65.42)
        val second = controller.state.value.focusRequest
        assertNotNull(second)
        assertEquals(-24.79, second!!.latitude, 1e-6)
        assertEquals(-65.42, second.longitude, 1e-6)
        assertNull(second.zoom)
    }

    @Test
    fun focusNowBeforeFirstFixAppliesDefaultZoomWhenFixArrives() = withController { controller, _ ->
        controller.focusNow(defaultZoom = 16.0)
        assertTrue(controller.state.value.isFollowing)
        assertNull(controller.state.value.focusRequest)

        controller.onLocationUpdated(-24.78, -65.41)
        val req = controller.state.value.focusRequest
        assertNotNull(req)
        assertEquals(16.0, req!!.zoom!!, 1e-6)
    }

    @Test
    fun zoomButtonKeepsFollowingWhenActiveAndResetsTimerWhenIdle() = withController { controller, clock ->
        assertEquals(1, clock.delayStarts)
        controller.onZoomButtonPressed()
        assertFalse(controller.state.value.isFollowing)
        assertEquals(2, clock.delayStarts)

        controller.focusNow(defaultZoom = 17.0)
        assertTrue(controller.state.value.isFollowing)

        controller.onZoomButtonPressed()
        assertTrue(controller.state.value.isFollowing)
        assertEquals(2, clock.delayStarts)
    }
}
