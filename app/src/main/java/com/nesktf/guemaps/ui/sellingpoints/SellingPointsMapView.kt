package com.nesktf.guemaps.ui.sellingpoints

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.view.animation.DecelerateInterpolator
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
import com.nesktf.guemaps.R
import com.nesktf.guemaps.data.model.SellingPoint
import com.nesktf.guemaps.ui.map.MapActions
import com.nesktf.guemaps.ui.map.MapCameraState
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun SellingPointsMapView(
    sellingPoints: List<SellingPoint> = emptyList(),
    selectedSellingPoint: SellingPoint? = null,
    userLocation: Location? = null,
    cameraState: MapCameraState = MapCameraState(),
    onCameraMoved: (Double, Double, Double) -> Unit = { _, _, _ -> },
    onSelectSellingPoint: (SellingPoint) -> Unit = {},
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

    val sellingPointsFolder = remember { FolderOverlay() }
    val userLocationFolder = remember { FolderOverlay() }

    // Pre-create and cache marker icons
    val atmIcon = remember(context) { createSellingPointMarkerDrawable(context, isAtm = true, isSelected = false) }
    val atmSelectedIcon = remember(context) { createSellingPointMarkerDrawable(context, isAtm = true, isSelected = true) }
    val storeIcon = remember(context) { createSellingPointMarkerDrawable(context, isAtm = false, isSelected = false) }
    val storeSelectedIcon = remember(context) { createSellingPointMarkerDrawable(context, isAtm = false, isSelected = true) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(cameraState.zoomLevel.takeIf { it > 0 } ?: 14.0)
            controller.setCenter(GeoPoint(cameraState.latitude, cameraState.longitude))

            // Constrain scrolling and zoom to Province of Salta
            val saltaBounds = BoundingBox(-21.90, -62.30, -26.50, -68.60)
            setScrollableAreaLimitDouble(saltaBounds)
            minZoomLevel = 8.0
            maxZoomLevel = 19.0

            overlays.add(sellingPointsFolder)
            overlays.add(userLocationFolder)

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

            // Smooth snap to integer zoom after pinch gesture ends
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

    // Connect mapActions
    LaunchedEffect(mapActions, sellingPoints) {
        mapActions.zoomIn = {
            val target = (Math.floor(mapView.zoomLevelDouble) + 1.0).coerceAtMost(19.0)
            zoomAnimator.animate(mapView, target, 200L)
        }
        mapActions.zoomOut = {
            val target = (Math.ceil(mapView.zoomLevelDouble) - 1.0).coerceAtLeast(8.0)
            zoomAnimator.animate(mapView, target, 200L)
        }
        mapActions.resetMap = {
            zoomAnimator.animate(mapView, 14.0, 300L)
            mapView.controller.animateTo(GeoPoint(-24.7859, -65.4117))
        }
        mapActions.animateToLocation = { lat, lon, zoom ->
            val snapped = Math.round(zoom).toDouble().coerceIn(8.0, 19.0)
            zoomAnimator.animate(mapView, snapped, 300L)
            mapView.controller.animateTo(GeoPoint(lat, lon))
        }
    }

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

    // Update selling points markers
    LaunchedEffect(sellingPoints, selectedSellingPoint) {
        sellingPointsFolder.items.clear()
        sellingPoints.forEach { point ->
            val lat = point.latitud
            val lon = point.longitud
            if (lat != null && lon != null) {
                val isSelected = point.id == selectedSellingPoint?.id
                val marker = Marker(mapView).apply {
                    position = GeoPoint(lat, lon)
                    icon = when {
                        point.isAtm && isSelected -> atmSelectedIcon
                        point.isAtm -> atmIcon
                        isSelected -> storeSelectedIcon
                        else -> storeIcon
                    }
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = point.nombre
                    snippet = point.domicilio
                    setOnMarkerClickListener { _, _ ->
                        onSelectSellingPoint(point)
                        true
                    }
                }
                sellingPointsFolder.add(marker)
            }
        }
        mapView.invalidate()
    }

    // Update user location layer
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

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

private fun createSellingPointMarkerDrawable(context: Context, isAtm: Boolean, isSelected: Boolean): Drawable {
    val density = context.resources.displayMetrics.density
    val baseSize = if (isSelected) 40 else 32
    val size = (baseSize * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgColor = if (isAtm) Color.parseColor("#0284C7") else Color.parseColor("#D97706")
    val selectedBorderColor = Color.parseColor("#FBBF24")

    // Shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44000000")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, (size / 2f) + (2f * density), (size / 2f) - (3f * density), shadowPaint)

    // Background circle
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - (3f * density), bgPaint)

    // Border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelected) selectedBorderColor else Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = (if (isSelected) 3.5f else 2f) * density
    }
    canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - (3f * density), borderPaint)

    // Center icon
    val drawableRes = if (isAtm) R.drawable.ic_atm else R.drawable.ic_store
    val icon = ContextCompat.getDrawable(context, drawableRes)
    if (icon != null) {
        val iconSize = (18 * density).toInt()
        val left = (size - iconSize) / 2
        val top = (size - iconSize) / 2
        icon.setBounds(left, top, left + iconSize, top + iconSize)
        icon.setTint(Color.WHITE)
        icon.draw(canvas)
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
