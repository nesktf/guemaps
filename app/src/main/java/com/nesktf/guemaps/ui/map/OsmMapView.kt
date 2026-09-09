package com.nesktf.guemaps.ui.map

import android.content.Context
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nesktf.guemaps.data.model.BusNode
import com.nesktf.guemaps.data.model.BusPos
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun OsmMapView(
    routeNodes: List<BusNode>,
    activeBuses: List<BusPos>,
    showStops: Boolean,
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
        }
    }

    // Manage MapView lifecycle with the Compose Lifecycle
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

    // Update map overlays when routeNodes, activeBuses, or showStops change
    LaunchedEffect(routeNodes, activeBuses, showStops) {
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
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(stop.latitud, stop.longitud)
                        icon = stopDrawable
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = stop.descripcionParada?.takeIf { it.isNotBlank() } ?: "Parada de colectivo"
                        snippet = if (!stop.codigoParada.isNullOrBlank()) "Código: ${stop.codigoParada}" else null
                    }
                    mapView.overlays.add(marker)
                }
            }
        }

        // 3. Draw Active Buses
        activeBuses.forEach { bus ->
            val busDrawable = createBusMarkerDrawable(
                context = context,
                busNumber = bus.interno,
                hasRamp = bus.vehiculoRampa
            )
            val marker = Marker(mapView).apply {
                position = GeoPoint(bus.latitud, bus.longitud)
                icon = busDrawable
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Colectivo #${bus.interno}"
                snippet = buildString {
                    if (bus.vehiculoRampa) append("♿ Con rampa accesible\n")
                    if (!bus.proximaParada.isNullOrBlank()) {
                        append("Próxima parada: ${bus.proximaParada}")
                    }
                }.trim()
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
                // Ignore if bounds calculation fails
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

private fun createBusMarkerDrawable(
    context: Context,
    busNumber: String,
    hasRamp: Boolean
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val width = (42 * density).toInt()
    val height = (26 * density).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Background badge
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (hasRamp) Color.parseColor("#15803D") else Color.parseColor("#D97706") // Green or Amber
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }

    val rect = RectF(
        2 * density,
        2 * density,
        width - 2 * density,
        height - 2 * density
    )
    val cornerRadius = 6 * density
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

    // Text
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11 * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    val label = if (hasRamp) "♿ $busNumber" else busNumber
    val yPos = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(label, width / 2f, yPos, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}
