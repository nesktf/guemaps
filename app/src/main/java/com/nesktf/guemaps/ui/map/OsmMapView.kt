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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import android.location.Location
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
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
    routeNodes: List<BusNode>,
    activeBuses: List<BusPos>,
    showStops: Boolean,
    userLocation: Location? = null,
    busLiveDetails: Map<String, BusLiveDetails> = emptyMap(),
    onBusSelected: (String) -> Unit = {},
    mapActions: MapActions = remember { MapActions() },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(13.5)
            // Center of Salta, Argentina
            controller.setCenter(GeoPoint(-24.7859, -65.4117))

            // Constrain scrolling and zoom to the Province of Salta
            // North: -21.90, East: -62.30, South: -26.50, West: -68.60
            val saltaBounds = BoundingBox(-21.90, -62.30, -26.50, -68.60)
            setScrollableAreaLimitDouble(saltaBounds)
            minZoomLevel = 8.5
            maxZoomLevel = 20.0
        }
    }

    // Connect mapActions to this MapView instance
    LaunchedEffect(mapActions, routeNodes) {
        mapActions.zoomIn = {
            mapView.controller.zoomIn()
        }
        mapActions.zoomOut = {
            mapView.controller.zoomOut()
        }
        mapActions.resetMap = {
            if (routeNodes.size > 1) {
                val geoPoints = routeNodes.map { GeoPoint(it.latitud, it.longitud) }
                try {
                    val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
                    mapView.zoomToBoundingBox(boundingBox, true, 120)
                } catch (e: Exception) {
                    mapView.controller.setZoom(13.5)
                    mapView.controller.animateTo(GeoPoint(-24.7859, -65.4117))
                }
            } else {
                mapView.controller.setZoom(13.5)
                mapView.controller.animateTo(GeoPoint(-24.7859, -65.4117))
            }
        }
        mapActions.animateToLocation = { lat, lon, zoom ->
            mapView.controller.setZoom(zoom)
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
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Update map overlays when routeNodes, activeBuses, showStops, or userLocation change
    LaunchedEffect(routeNodes, activeBuses, showStops, userLocation, busLiveDetails) {
        mapView.overlays.clear()

        // 1. Draw Route Polyline
        if (routeNodes.isNotEmpty()) {
            val geoPoints = routeNodes.map { GeoPoint(it.latitud, it.longitud) }
            val polyline = Polyline(mapView).apply {
                outlinePaint.color = Color.parseColor("#2563EB") // Vibrant Indigo/Blue
                outlinePaint.strokeWidth = 12f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
                setPoints(geoPoints)
            }
            mapView.overlays.add(polyline)

            // 2. Draw Bus Stops
            if (showStops) {
                val stopDrawable = getCachedStopMarkerDrawable(context)
                routeNodes.filter { it.parada }.forEach { stop ->
                    val titleText = stop.descripcionParada?.takeIf { it.isNotBlank() }
                    val snippetText = stop.codigoParada?.takeIf { it.isNotBlank() }?.let { "Código: $it" }

                    val marker = Marker(mapView).apply {
                        position = GeoPoint(stop.latitud, stop.longitud)
                        icon = stopDrawable
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

                        if (titleText != null || snippetText != null) {
                            title = titleText ?: "Parada de colectivo"
                            snippet = snippetText
                        } else {
                            // Disable popup if no information is available on this node!
                            infoWindow = null
                            setOnMarkerClickListener { _, _ -> true }
                        }
                    }
                    mapView.overlays.add(marker)
                }
            }
        }

        // 3. User Location Marker (Fixed directly to map coordinates, cannot move with screen panning!)
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
                mapView.overlays.add(accuracyCircle)
            }

            val userDrawable = BitmapDrawable(context.resources, createPersonLocationBitmap(context))
            val userMarker = Marker(mapView).apply {
                position = GeoPoint(userLocation.latitude, userLocation.longitude)
                icon = userDrawable
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Tu ubicación"
                snippet = if (userLocation.hasAccuracy()) "Precisión: ±%.0fm".format(userLocation.accuracy) else null
            }
            mapView.overlays.add(userMarker)
        }

        // 4. Draw Active Buses with Pretty Bus SVG/Vector Icon
        activeBuses.forEach { bus ->
            val busDrawable = createPrettyBusMarkerDrawable(
                context = context,
                busNumber = bus.interno,
                hasRamp = bus.vehiculoRampa
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
            mapView.overlays.add(marker)
        }

        mapView.invalidate()
    }

    // Auto-fit bounds when route changes
    LaunchedEffect(routeNodes) {
        if (routeNodes.size > 1) {
            val geoPoints = routeNodes.map { GeoPoint(it.latitud, it.longitud) }
            try {
                val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
                mapView.post {
                    mapView.zoomToBoundingBox(boundingBox, true, 120)
                }
            } catch (e: Exception) {
                // Ignore bounds calculation failure
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

// Marker Cache and Generators

private var cachedStopDrawable: BitmapDrawable? = null

private fun getCachedStopMarkerDrawable(context: Context): BitmapDrawable {
    cachedStopDrawable?.let { return it }

    val density = context.resources.displayMetrics.density
    val size = (14 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0284C7") // Cyan/Blue
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0369A1")
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }

    val radius = size / 2f
    canvas.drawCircle(radius, radius, radius, outerPaint)
    canvas.drawCircle(radius, radius, radius - (2 * density), innerPaint)
    canvas.drawCircle(radius, radius, radius - (1 * density), borderPaint)

    val drawable = BitmapDrawable(context.resources, bitmap)
    cachedStopDrawable = drawable
    return drawable
}

private fun createPrettyBusMarkerDrawable(
    context: Context,
    busNumber: String,
    hasRamp: Boolean
): BitmapDrawable {
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

    // 3. Bus Body
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D4ED8") // Modern Transit Blue
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

    return BitmapDrawable(context.resources, bitmap)
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
