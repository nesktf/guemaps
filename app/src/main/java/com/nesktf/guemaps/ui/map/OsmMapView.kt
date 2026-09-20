package com.nesktf.guemaps.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nesktf.guemaps.data.model.BusLiveDetails
import com.nesktf.guemaps.data.model.BusNode
import com.nesktf.guemaps.data.model.BusPos
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import android.location.Location
import android.view.MotionEvent
import android.widget.Toast
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

class MapActions {
    var zoomIn: () -> Unit = {}
    var zoomOut: () -> Unit = {}
    var resetMap: () -> Unit = {}
    var animateToLocation: (Double, Double, Double) -> Unit = { _, _, _ -> }
}

@Composable
fun OsmMapView(
    routeNodes: List<BusNode> = emptyList(),
    activeBuses: List<BusPos> = emptyList(),
    activeLines: List<ActiveLineData> = emptyList(),
    showStops: Boolean,
    userLocation: Location? = null,
    busLiveDetails: Map<String, BusLiveDetails> = emptyMap(),
    cameraState: MapCameraState = MapCameraState(),
    shouldFitRouteBounds: Boolean = false,
    onRouteBoundsFitted: () -> Unit = {},
    onCameraMoved: (Double, Double, Double) -> Unit = { _, _, _ -> },
    onBusSelected: (String) -> Unit = {},
    mapActions: MapActions = remember { MapActions() },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val zoomAnimator = remember {
        object {
            var animator: ValueAnimator? = null
            fun animate(mapView: MapView, targetZoom: Double, durationMs: Long = 250L) {
                animator?.cancel()
                val currentZoom = mapView.zoomLevelDouble
                if (Math.abs(currentZoom - targetZoom) < 0.01) return
                animator = ValueAnimator.ofFloat(currentZoom.toFloat(), targetZoom.toFloat()).apply {
                    duration = durationMs
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { anim ->
                        mapView.controller.setZoom((anim.animatedValue as Float).toDouble())
                    }
                    start()
                }
            }
            fun cancel() {
                animator?.cancel()
                animator = null
            }
        }
    }

    val routesFolder = remember { FolderOverlay() }
    val fastStopsOverlay = remember { FastStopsOverlay(context) }
    val userLocationFolder = remember { FolderOverlay() }
    val busesFolder = remember { FolderOverlay() }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(cameraState.zoomLevel.takeIf { it > 0 } ?: 15.0)
            controller.setCenter(GeoPoint(cameraState.centerLat, cameraState.centerLon))

            // Constrain scrolling and zoom to the Province of Salta
            // North: -21.90, East: -62.30, South: -26.50, West: -68.60
            val saltaBounds = BoundingBox(-21.90, -62.30, -26.50, -68.60)
            setScrollableAreaLimitDouble(saltaBounds)
            minZoomLevel = 8.0
            maxZoomLevel = 19.0

            // Add persistent overlay layers in bottom-to-top rendering order
            overlays.add(routesFolder)
            overlays.add(fastStopsOverlay)
            overlays.add(userLocationFolder)
            overlays.add(busesFolder)

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    val center = mapCenter
                    if (center != null) {
                        onCameraMoved(center.latitude, center.longitude, zoomLevelDouble)
                    }
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    val center = mapCenter
                    if (center != null) {
                        onCameraMoved(center.latitude, center.longitude, zoomLevelDouble)
                    }
                    return false
                }
            })

            // Smoothly snap to integer zoom after pinch gesture ends to ensure 1:1 pixel crisp tiles
            var isPinching = false
            var snapRunnable: Runnable? = null

            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                        isPinching = true
                        snapRunnable?.let { removeCallbacks(it) }
                        zoomAnimator.cancel()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (isPinching) {
                            isPinching = false
                            snapRunnable?.let { removeCallbacks(it) }
                            snapRunnable = Runnable {
                                val target = Math.round(zoomLevelDouble).toDouble().coerceIn(minZoomLevel, maxZoomLevel)
                                if (Math.abs(zoomLevelDouble - target) > 0.02) {
                                    zoomAnimator.animate(this, target, 250L)
                                }
                            }
                            postDelayed(snapRunnable, 100)
                        }
                    }
                }
                false
            }
        }
    }

    // Connect mapActions to this MapView instance
    LaunchedEffect(mapActions, routeNodes, activeLines) {
        mapActions.zoomIn = {
            val target = (Math.floor(mapView.zoomLevelDouble) + 1.0).coerceAtMost(19.0)
            zoomAnimator.animate(mapView, target, 200L)
        }
        mapActions.zoomOut = {
            val target = (Math.ceil(mapView.zoomLevelDouble) - 1.0).coerceAtLeast(8.0)
            zoomAnimator.animate(mapView, target, 200L)
        }
        mapActions.resetMap = {
            val allNodes = if (activeLines.isNotEmpty()) activeLines.flatMap { it.routeNodes } else routeNodes
            if (allNodes.size > 1) {
                val geoPoints = allNodes.map { GeoPoint(it.latitud, it.longitud) }
                try {
                    val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
                    mapView.zoomToBoundingBox(boundingBox, false, 120)
                    val snapped = (Math.round(mapView.zoomLevelDouble) + 1.0).coerceIn(10.0, 18.0)
                    zoomAnimator.animate(mapView, snapped, 300L)
                } catch (e: Exception) {
                    zoomAnimator.animate(mapView, 15.0, 300L)
                    mapView.controller.animateTo(GeoPoint(-24.7859, -65.4117))
                }
            } else {
                zoomAnimator.animate(mapView, 15.0, 300L)
                mapView.controller.animateTo(GeoPoint(-24.7859, -65.4117))
            }
        }
        mapActions.animateToLocation = { lat, lon, zoom ->
            val snapped = Math.round(zoom).toDouble().coerceIn(8.0, 19.0)
            zoomAnimator.animate(mapView, snapped, 300L)
            mapView.controller.animateTo(GeoPoint(lat, lon))
        }
    }

    // Manage MapView lifecycle with Compose
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDetach()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            zoomAnimator.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // 1. Route polylines layer: ONLY updated when activeLines or routeNodes change
    LaunchedEffect(activeLines, routeNodes) {
        routesFolder.items.clear()
        if (activeLines.isNotEmpty()) {
            activeLines.forEach { lineData ->
                if (lineData.routeNodes.isNotEmpty()) {
                    val geoPoints = lineData.routeNodes.map { GeoPoint(it.latitud, it.longitud) }
                    val polyline = Polyline(mapView).apply {
                        outlinePaint.color = try { Color.parseColor(lineData.colorHex) } catch (_: Exception) { Color.parseColor("#35399D") }
                        outlinePaint.strokeWidth = 11f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        outlinePaint.isAntiAlias = true
                        isGeodesic = false
                        setPoints(geoPoints)
                    }
                    routesFolder.add(polyline)
                }
            }
        } else if (routeNodes.isNotEmpty()) {
            val geoPoints = routeNodes.map { GeoPoint(it.latitud, it.longitud) }
            val polyline = Polyline(mapView).apply {
                outlinePaint.color = Color.parseColor("#35399D")
                outlinePaint.strokeWidth = 11f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
                isGeodesic = false
                setPoints(geoPoints)
            }
            routesFolder.add(polyline)
        }
        mapView.invalidate()
    }

    // 2. High-performance stops layer: single custom overlay with viewport culling
    LaunchedEffect(activeLines, routeNodes, showStops) {
        fastStopsOverlay.updateData(activeLines, showStops, fallback = routeNodes)
        mapView.invalidate()
    }

    // 3. User location layer: ONLY updated when userLocation changes
    LaunchedEffect(userLocation) {
        userLocationFolder.items.clear()
        if (userLocation != null) {
            if (userLocation.hasAccuracy() && userLocation.accuracy > 0f) {
                val accuracyCircle = Polygon(mapView).apply {
                    points = Polygon.pointsAsCircle(
                        GeoPoint(userLocation.latitude, userLocation.longitude),
                        userLocation.accuracy.toDouble()
                    )
                    fillPaint.color = Color.parseColor("#222563EB")
                    fillPaint.style = Paint.Style.FILL
                    outlinePaint.color = Color.parseColor("#602563EB")
                    outlinePaint.strokeWidth = 2f
                    outlinePaint.style = Paint.Style.STROKE
                }
                userLocationFolder.add(accuracyCircle)
            }

            val userDrawable = BitmapDrawable(context.resources, createPersonLocationBitmap(context))
            val userMarker = Marker(mapView).apply {
                position = GeoPoint(userLocation.latitude, userLocation.longitude)
                icon = userDrawable
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Tu ubicación"
                snippet = if (userLocation.hasAccuracy()) "Precisión: ±%.0fm".format(userLocation.accuracy) else null
            }
            userLocationFolder.add(userMarker)
        }
        mapView.invalidate()
    }

    // 4. Live buses layer: updated when activeBuses change (does NOT re-create routes or stops!)
    LaunchedEffect(activeLines, activeBuses) {
        busesFolder.items.clear()
        if (activeLines.isNotEmpty()) {
            activeLines.forEach { lineData ->
                lineData.activeBuses.forEach { bus ->
                    val busDrawable = createPrettyBusMarkerDrawable(
                        context = context,
                        busNumber = bus.interno,
                        hasRamp = bus.vehiculoRampa,
                        colorHex = lineData.colorHex
                    )

                    val marker = Marker(mapView).apply {
                        position = GeoPoint(bus.latitud, bus.longitud)
                        icon = busDrawable
                        setAnchor(Marker.ANCHOR_CENTER, 0.85f)
                        title = "Línea ${lineData.line.nombreCorto} - Coche #${bus.interno}"
                        snippet = if (bus.vehiculoRampa) "♿ Accesible con rampa" else lineData.line.nombreLinea
                        setOnMarkerClickListener { m, _ ->
                            onBusSelected(bus.interno)
                            m.showInfoWindow()
                            true
                        }
                    }
                    busesFolder.add(marker)
                }
            }
        } else {
            activeBuses.forEach { bus ->
                val busDrawable = createPrettyBusMarkerDrawable(
                    context = context,
                    busNumber = bus.interno,
                    hasRamp = bus.vehiculoRampa,
                    colorHex = "#35399D"
                )

                val marker = Marker(mapView).apply {
                    position = GeoPoint(bus.latitud, bus.longitud)
                    icon = busDrawable
                    setAnchor(Marker.ANCHOR_CENTER, 0.85f)
                    title = "Colectivo #${bus.interno}"
                    snippet = if (bus.vehiculoRampa) "♿ Accesible con rampa" else null
                    setOnMarkerClickListener { m, _ ->
                        onBusSelected(bus.interno)
                        m.showInfoWindow()
                        true
                    }
                }
                busesFolder.add(marker)
            }
        }
        mapView.invalidate()
    }

    // Auto-fit bounds ONLY when selecting a new line
    LaunchedEffect(routeNodes, activeLines, shouldFitRouteBounds) {
        val allNodes = if (activeLines.isNotEmpty()) activeLines.flatMap { it.routeNodes } else routeNodes
        if (shouldFitRouteBounds && allNodes.size > 1) {
            val geoPoints = allNodes.map { GeoPoint(it.latitud, it.longitud) }
            try {
                val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
                mapView.post {
                    mapView.zoomToBoundingBox(boundingBox, false, 120)
                    val snapped = (Math.round(mapView.zoomLevelDouble) + 1.0).coerceIn(10.0, 18.0)
                    zoomAnimator.animate(mapView, snapped, 300L)
                    onRouteBoundsFitted()
                }
            } catch (e: Exception) {
                onRouteBoundsFitted()
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

// Marker Cache and Generators

class FastStopsOverlay(
    private val context: Context
) : Overlay() {
    private var activeLines: List<ActiveLineData> = emptyList()
    private var fallbackNodes: List<BusNode> = emptyList()
    private var showStops: Boolean = true

    private val screenPoint = android.graphics.Point()
    private val clickPoint = android.graphics.Point()
    private val density = context.resources.displayMetrics.density
    private val stopRadius = 6.5f * density
    private val borderStroke = 2.0f * density

    private val fillPaints = mutableMapOf<String, Paint>()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = borderStroke
    }

    fun updateData(lines: List<ActiveLineData>, show: Boolean, fallback: List<BusNode> = emptyList()) {
        this.activeLines = lines
        this.showStops = show
        this.fallbackNodes = fallback
    }

    private fun getFillPaint(colorHex: String): Paint {
        return fillPaints.getOrPut(colorHex) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.parseColor("#35399D") }
                style = Paint.Style.FILL
            }
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !showStops) return

        // At zoom levels below 13.0, stops are too dense and obscure the streets
        val zoom = mapView.zoomLevelDouble
        if (zoom < 13.0) return

        val projection = mapView.projection ?: return
        val mapBounds = mapView.boundingBox ?: return

        // 10% viewport margin for smooth edge panning
        val latMargin = (mapBounds.latNorth - mapBounds.latSouth) * 0.1
        val lonMargin = (mapBounds.lonEast - mapBounds.lonWest) * 0.1
        val minLat = mapBounds.latSouth - latMargin
        val maxLat = mapBounds.latNorth + latMargin
        val minLon = mapBounds.lonWest - lonMargin
        val maxLon = mapBounds.lonEast + lonMargin

        if (activeLines.isNotEmpty()) {
            for (lineData in activeLines) {
                val fillPaint = getFillPaint(lineData.colorHex)
                val nodes = lineData.routeNodes
                for (i in nodes.indices) {
                    val node = nodes[i]
                    if (!node.parada) continue

                    val lat = node.latitud
                    val lon = node.longitud
                    if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) continue

                    projection.toPixels(GeoPoint(lat, lon), screenPoint)
                    canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), stopRadius, fillPaint)
                    canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), stopRadius, borderPaint)
                }
            }
        } else if (fallbackNodes.isNotEmpty()) {
            val fillPaint = getFillPaint("#35399D")
            for (i in fallbackNodes.indices) {
                val node = fallbackNodes[i]
                if (!node.parada) continue

                val lat = node.latitud
                val lon = node.longitud
                if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) continue

                projection.toPixels(GeoPoint(lat, lon), screenPoint)
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), stopRadius, fillPaint)
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), stopRadius, borderPaint)
            }
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        if (!showStops || mapView.zoomLevelDouble < 13.0) return false
        val projection = mapView.projection ?: return false
        val touchX = e.x
        val touchY = e.y
        val touchRadiusSq = (26f * density) * (26f * density) // 26dp touch radius

        if (activeLines.isNotEmpty()) {
            for (lineData in activeLines) {
                for (node in lineData.routeNodes) {
                    if (!node.parada) continue
                    projection.toPixels(GeoPoint(node.latitud, node.longitud), clickPoint)
                    val dx = clickPoint.x - touchX
                    val dy = clickPoint.y - touchY
                    if (dx * dx + dy * dy <= touchRadiusSq) {
                        val stopName = node.descripcionParada?.takeIf { it.isNotBlank() } ?: "Parada de colectivo"
                        val code = node.codigoParada?.takeIf { it.isNotBlank() }?.let { " (Código: $it)" } ?: ""
                        Toast.makeText(context, "$stopName$code\nLínea ${lineData.line.nombreCorto}", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }
            }
        } else if (fallbackNodes.isNotEmpty()) {
            for (node in fallbackNodes) {
                if (!node.parada) continue
                projection.toPixels(GeoPoint(node.latitud, node.longitud), clickPoint)
                val dx = clickPoint.x - touchX
                val dy = clickPoint.y - touchY
                if (dx * dx + dy * dy <= touchRadiusSq) {
                    val stopName = node.descripcionParada?.takeIf { it.isNotBlank() } ?: "Parada de colectivo"
                    val code = node.codigoParada?.takeIf { it.isNotBlank() }?.let { " (Código: $it)" } ?: ""
                    Toast.makeText(context, "$stopName$code", Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        }
        return false
    }
}

private val stopDrawableCache = mutableMapOf<String, BitmapDrawable>()

private fun getCachedStopMarkerDrawable(context: Context, colorHex: String = "#35399D"): BitmapDrawable {
    stopDrawableCache[colorHex]?.let { return it }

    val density = context.resources.displayMetrics.density
    val size = (14 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.parseColor("#35399D") }
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }

    val radius = size / 2f
    canvas.drawCircle(radius, radius, radius, outerPaint)
    canvas.drawCircle(radius, radius, radius - (2 * density), innerPaint)
    canvas.drawCircle(radius, radius, radius - (1 * density), borderPaint)

    val drawable = BitmapDrawable(context.resources, bitmap)
    stopDrawableCache[colorHex] = drawable
    return drawable
}

private val busDrawableCache = mutableMapOf<String, BitmapDrawable>()

private fun createPrettyBusMarkerDrawable(
    context: Context,
    busNumber: String,
    hasRamp: Boolean,
    colorHex: String = "#35399D"
): BitmapDrawable {
    val cacheKey = "${busNumber}_${hasRamp}_${colorHex}"
    busDrawableCache[cacheKey]?.let { return it }

    val density = context.resources.displayMetrics.density

    // Text paint for bus number
    val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10 * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    val numWidth = numPaint.measureText(busNumber)
    val busWidth = maxOf((46 * density).toInt(), (numWidth + 18 * density).toInt())
    val busHeight = (36 * density).toInt()
    val totalHeight = busHeight + (10 * density).toInt() // bottom pointer / clearance
    val rampExtra = if (hasRamp) (12 * density).toInt() else 0
    val totalWidth = busWidth + rampExtra

    val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val busLeft = 2 * density
    val busTop = 6 * density
    val busRight = busLeft + busWidth - (4 * density)
    val busBottom = busTop + busHeight - (4 * density)

    // 1. Bus Ground Shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#35000000")
        style = Paint.Style.FILL
    }
    val shadowRect = RectF(busLeft + (4 * density), busBottom, busRight - (4 * density), busBottom + (6 * density))
    canvas.drawOval(shadowRect, shadowPaint)

    // 2. Wheels
    val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B") // Dark Slate
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(RectF(busLeft + (4 * density), busBottom - (4 * density), busLeft + (10 * density), busBottom + (2 * density)), 2 * density, 2 * density, wheelPaint)
    canvas.drawRoundRect(RectF(busRight - (10 * density), busBottom - (4 * density), busRight - (4 * density), busBottom + (2 * density)), 2 * density, 2 * density, wheelPaint)

    // 3. Bus Body (Dynamic Hex Color!)
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.parseColor("#35399D") }
        style = Paint.Style.FILL
    }
    val bodyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    val bodyRect = RectF(busLeft, busTop, busRight, busBottom)
    val cornerR = 7 * density
    canvas.drawRoundRect(bodyRect, cornerR, cornerR, bodyPaint)
    canvas.drawRoundRect(bodyRect, cornerR, cornerR, bodyBorderPaint)

    // 4. Destination Sign Banner (Header with bus number)
    val signPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A") // Deep Black/Slate
        style = Paint.Style.FILL
    }
    val signRect = RectF(busLeft + (4 * density), busTop + (3 * density), busRight - (4 * density), busTop + (15 * density))
    canvas.drawRoundRect(signRect, 3 * density, 3 * density, signPaint)

    val numY = signRect.centerY() - ((numPaint.descent() + numPaint.ascent()) / 2f)
    canvas.drawText(busNumber, signRect.centerX(), numY, numPaint)

    // 5. Panoramic Front Windshield
    val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0F2FE") // Sky Glass
        style = Paint.Style.FILL
    }
    val glassRect = RectF(busLeft + (4 * density), busTop + (16 * density), busRight - (4 * density), busTop + (25 * density))
    canvas.drawRoundRect(glassRect, 3 * density, 3 * density, glassPaint)

    val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 1.2f * density
    }
    canvas.drawLine(glassRect.centerX(), glassRect.top, glassRect.centerX(), glassRect.bottom, dividerPaint)

    // 6. Dual Headlights
    val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FEF08A") // Warm Headlight Glow
        style = Paint.Style.FILL
    }
    val lightY = busBottom - (4 * density)
    canvas.drawCircle(busLeft + (7 * density), lightY, 2.2f * density, lightPaint)
    canvas.drawCircle(busRight - (7 * density), lightY, 2.2f * density, lightPaint)

    // 7. Wheelchair Ramp Badge (♿)
    if (hasRamp) {
        val rampSize = 17 * density
        val rampLeft = totalWidth - rampSize - (1 * density)
        val rampTop = 1f * density
        val rampRect = RectF(rampLeft, rampTop, rampLeft + rampSize, rampTop + rampSize)

        val rampBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#15803D") // Vibrant Green
            style = Paint.Style.FILL
        }
        val rampBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        canvas.drawCircle(rampRect.centerX(), rampRect.centerY(), rampSize / 2f, rampBgPaint)
        canvas.drawCircle(rampRect.centerX(), rampRect.centerY(), rampSize / 2f, rampBorderPaint)

        val rampTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9.5f * density
            textAlign = Paint.Align.CENTER
        }
        val rampY = rampRect.centerY() - ((rampTextPaint.descent() + rampTextPaint.ascent()) / 2f)
        canvas.drawText("♿", rampRect.centerX(), rampY, rampTextPaint)
    }

    val drawable = BitmapDrawable(context.resources, bitmap)
    busDrawableCache[cacheKey] = drawable
    return drawable
}

private fun createPersonLocationBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (24 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        alpha = 80
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, outerPaint)

    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    canvas.drawCircle(size / 2f, size / 2f, 6.5f * density, innerPaint)
    canvas.drawCircle(size / 2f, size / 2f, 6.5f * density, borderPaint)

    return bitmap
}
