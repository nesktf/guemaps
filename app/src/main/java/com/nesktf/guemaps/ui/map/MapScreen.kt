package com.nesktf.guemaps.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import com.nesktf.guemaps.data.model.BusPreset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nesktf.guemaps.data.model.BusEntry
import com.nesktf.guemaps.data.model.FlatBusLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onOpenAbout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val mapActions = remember { MapActions() }

    // Intercept nested scroll deltas to prevent bottom sheet jitter when scrolling fast
    val noOverscrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return available
            }
        }
    }

    val context = LocalContext.current
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            viewModel.requestUserLocation { loc ->
                mapActions.animateToLocation(loc.latitude, loc.longitude, 17.0)
            }
        }
    }

    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<BusPreset?>(null) }

    // Intercept back button when line picker bottom sheet is open
    BackHandler(enabled = state.isLinePickerOpen) {
        viewModel.setLinePickerOpen(false)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Map View
        OsmMapView(
            routeNodes = state.routeNodes,
            activeBuses = state.activeBuses,
            activeLines = state.activeLines.values.toList(),
            showStops = state.showStops,
            userLocation = state.userLocation,
            busLiveDetails = state.busLiveDetails,
            cameraState = state.cameraState,
            shouldFitRouteBounds = state.shouldFitRouteBounds,
            onRouteBoundsFitted = { viewModel.onRouteBoundsFitted() },
            onCameraMoved = { lat, lon, zoom -> viewModel.updateMapCamera(lat, lon, zoom) },
            onBusSelected = { viewModel.selectBusForFloatingCard(it) },
            mapActions = mapActions,
            modifier = Modifier.fillMaxSize()
        )

        // Top Header / Selector Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Offline Warning Banner (Only visible when falling back to cache due to network failure)
            if (state.isGroupsOffline || state.isRouteOffline) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = "Offline",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Modo sin conexión: visualizando datos guardados",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Line Selector Floating Card (Multi-line Support)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setLinePickerOpen(true) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsBus,
                            contentDescription = "Líneas",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.selectedLines.isEmpty()) "Seleccionar Líneas" else "Líneas Activas (${state.selectedLines.size}/4)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.selectedLines.isEmpty()) {
                                Text(
                                    text = "Toque aquí para agregar hasta 4 colectivos",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (state.isLoadingRoute) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        if (state.selectedLines.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.clearAllLines() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Limpiar todas las líneas",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        IconButton(
                            onClick = { viewModel.setLinePickerOpen(true) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Agregar línea",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Display active line badges horizontally with colors and quick remove
                    if (state.selectedLines.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(state.selectedLines, key = { it.codLinea }) { line ->
                                val activeData = state.activeLines[line.codLinea]
                                val colorInt = try {
                                    android.graphics.Color.parseColor(activeData?.colorHex ?: "#35399D")
                                } catch (_: Exception) {
                                    android.graphics.Color.BLUE
                                }
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(colorInt).copy(alpha = 0.15f),
                                    border = BorderStroke(1.5.dp, Color(colorInt)),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 10.dp, end = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(Color(colorInt))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = line.nombreCorto,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        IconButton(
                                            onClick = { viewModel.removeLine(line.codLinea) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Quitar línea",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Floating Bus Info Card (Speed and User-Relative Stop Distance)
            if (state.selectedLines.isNotEmpty() && state.activeBuses.isNotEmpty()) {
                val focusedInterno = state.selectedBusInterno ?: state.activeBuses.firstOrNull()?.interno
                val liveDetail = focusedInterno?.let { state.busLiveDetails[it] }

                if (liveDetail != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 5.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .align(Alignment.Start)
                            .widthIn(max = 240.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "#${liveDetail.bus.interno}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    if (liveDetail.bus.vehiculoRampa) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(text = "♿", style = MaterialTheme.typography.bodySmall)
                                    }
                                }

                                if (state.activeBuses.size > 1) {
                                    val curIdx = state.activeBuses.indexOfFirst { it.interno == focusedInterno }
                                    val nextBus = state.activeBuses[(curIdx + 1).coerceAtLeast(0) % state.activeBuses.size]
                                    IconButton(
                                        onClick = { viewModel.selectBusForFloatingCard(nextBus.interno) },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                                            contentDescription = "Siguiente colectivo",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Speed
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = liveDetail.formatSpeed(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            // Distance relative to nearest stop to user
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.NearMe,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .padding(top = 1.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    if (state.userLocation != null) {
                                        if (liveDetail.distanceToUserStopMeters != null) {
                                            Text(
                                                text = liveDetail.formatUserStopDistance(),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            if (!liveDetail.userNearestStopName.isNullOrBlank()) {
                                                Text(
                                                    text = liveDetail.userNearestStopName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "Calculando a tu parada...",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "GPS inactivo (distancia a tu parada)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // About Floating Action Button (Positioned cleanly at Bottom-Left, away from bus selector)
        FloatingActionButton(
            onClick = onOpenAbout,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .size(44.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Acerca de"
            )
        }

        // Floating Action Buttons (Right stack)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Zoom In (+)
            FloatingActionButton(
                onClick = { mapActions.zoomIn() },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Acercar mapa"
                )
            }

            // Zoom Out (-)
            FloatingActionButton(
                onClick = { mapActions.zoomOut() },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Alejar mapa"
                )
            }

            // Reset Map
            FloatingActionButton(
                onClick = { mapActions.resetMap() },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = "Restablecer mapa"
                )
            }

            // User Location
            FloatingActionButton(
                onClick = {
                    if (state.userLocation != null) {
                        mapActions.animateToLocation(
                            state.userLocation!!.latitude,
                            state.userLocation!!.longitude,
                            17.0
                        )
                    }

                    val fineGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val coarseGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (fineGranted || coarseGranted) {
                        viewModel.requestUserLocation { loc ->
                            mapActions.animateToLocation(loc.latitude, loc.longitude, 17.0)
                        }
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            ) {
                if (state.isLocatingUser) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Mi ubicación"
                    )
                }
            }

            // Toggle Stops
            FloatingActionButton(
                onClick = { viewModel.toggleStops() },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = if (state.showStops) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = if (state.showStops) Icons.Default.Place else Icons.Default.VisibilityOff,
                    contentDescription = "Toggle Paradas"
                )
            }

            // Refresh Active Buses
            if (state.selectedLines.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { viewModel.refreshActiveBuses() },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Recargar colectivos"
                    )
                }
            }
        }

        // Bottom Center Toasts (Active buses count & GPS loading indicator)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp, start = 72.dp, end = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Active buses toast
            if (state.selectedLines.isNotEmpty() && !state.isLoadingRoute) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val totalBuses = state.activeBuses.size
                        val rampBuses = state.activeBuses.count { it.vehiculoRampa }
                        val linesCount = state.selectedLines.size
                        Text(
                            text = when {
                                state.isLoadingBuses -> "Actualizando posiciones..."
                                state.busesErrorMessage != null -> state.busesErrorMessage ?: ""
                                totalBuses == 0 -> "No hay colectivos activos ($linesCount ${if (linesCount == 1) "línea" else "líneas"})"
                                totalBuses == 1 -> "1 colectivo activo en $linesCount ${if (linesCount == 1) "línea" else "líneas"}${if (rampBuses == 1) " (con rampa ♿)" else ""}"
                                else -> "$totalBuses colectivos activos en $linesCount ${if (linesCount == 1) "línea" else "líneas"} ($rampBuses con rampa ♿)"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            // GPS Location loading indicator
            AnimatedVisibility(
                visible = state.isLocatingUser,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Obteniendo ubicación GPS...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // Bottom Sheet Line Picker
        if (state.isLinePickerOpen) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.setLinePickerOpen(false) },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Seleccionar Líneas",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = if (state.selectedLines.size >= 4) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "${state.selectedLines.size}/4 seleccionadas",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (state.selectedLines.size >= 4) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (state.errorMessage != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Text(
                                text = state.errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // Search field
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("Buscar por nombre, corredor o línea...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Buscar")
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )

                    if (state.isLoadingGroups) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (state.errorMessage != null && state.flatLines.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = state.errorMessage ?: "Error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = { viewModel.loadBusGroups(forceNetwork = true) }) {
                                Text("Reintentar")
                            }
                        }
                    } else if (state.searchQuery.isNotBlank()) {
                        // Display search filtered results
                        Text(
                            text = "Resultados de búsqueda (${state.filteredLines.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .nestedScroll(noOverscrollConnection),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(state.filteredLines) { line ->
                                val isSelected = state.selectedLines.any { it.codLinea == line.codLinea }
                                val colorHex = state.activeLines[line.codLinea]?.colorHex
                                BusLineItem(
                                    line = line,
                                    isSelected = isSelected,
                                    assignedColorHex = colorHex,
                                    onClick = { viewModel.toggleLineSelection(line) }
                                )
                            }
                        }
                    } else {
                        // Category Chips: Activas, Ajustes, Todos, and Corridors
                        val allCategories = state.categories
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = state.selectedCategory == "ACTIVAS",
                                    onClick = {
                                        viewModel.selectCategory(
                                            if (state.selectedCategory == "ACTIVAS") null else "ACTIVAS"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.DirectionsBus,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text("Activas (${state.selectedLines.size})") }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = state.selectedCategory == "AJUSTES",
                                    onClick = {
                                        viewModel.selectCategory(
                                            if (state.selectedCategory == "AJUSTES") null else "AJUSTES"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Bookmark,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text("Ajustes (${state.presets.size})") }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = state.selectedCategory == null,
                                    onClick = { viewModel.selectCategory(null) },
                                    label = { Text("Todos") }
                                )
                            }
                            items(allCategories) { category ->
                                FilterChip(
                                    selected = state.selectedCategory == category.name,
                                    onClick = {
                                        viewModel.selectCategory(
                                            if (state.selectedCategory == category.name) null else category.name
                                        )
                                    },
                                    label = { Text(category.name) }
                                )
                            }
                        }

                        if (state.selectedCategory == "ACTIVAS") {
                            // Active Lines Tab: show active lines, ability to remove them, button to save preset, button to clear all
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { showSavePresetDialog = true },
                                        enabled = state.selectedLines.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BookmarkAdd,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Guardar ajuste")
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.clearAllLines() },
                                        enabled = state.selectedLines.isNotEmpty()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Limpiar", color = MaterialTheme.colorScheme.error)
                                    }
                                }

                                if (state.selectedLines.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No hay líneas activas actualmente.\nSeleccioná colectivos en 'Todos' o cargá un ajuste.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .nestedScroll(noOverscrollConnection),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(state.selectedLines, key = { "active_${it.codLinea}" }) { line ->
                                            val colorHex = state.activeLines[line.codLinea]?.colorHex ?: "#35399D"
                                            val lineAccentColor = try {
                                                Color(android.graphics.Color.parseColor(colorHex))
                                            } catch (_: Exception) {
                                                MaterialTheme.colorScheme.primary
                                            }
                                            Surface(
                                                color = lineAccentColor.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.5.dp, lineAccentColor),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(14.dp)
                                                            .clip(CircleShape)
                                                            .background(lineAccentColor)
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = line.descripcion,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = "${line.groupPath} • Línea ${line.codLinea}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = { viewModel.removeLine(line.codLinea) },
                                                        modifier = Modifier.size(36.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = "Quitar línea",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (state.selectedCategory == "AJUSTES") {
                            // Presets Tab: show list of saved presets, load button, delete button
                            if (state.presets.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No tenés ajustes guardados.\nConfigurá tus líneas y guardá un ajuste desde la pestaña 'Activas'.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .nestedScroll(noOverscrollConnection),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(state.presets, key = { it.id }) { preset ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = preset.name,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = "${preset.lines.size} ${if (preset.lines.size == 1) "colectivo" else "colectivos"}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }

                                                    Button(
                                                        onClick = {
                                                            viewModel.loadPreset(preset)
                                                            viewModel.setLinePickerOpen(false)
                                                        },
                                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.PlayArrow,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Cargar")
                                                    }

                                                    Spacer(modifier = Modifier.width(6.dp))

                                                    IconButton(
                                                        onClick = { presetToDelete = preset },
                                                        modifier = Modifier.size(36.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.DeleteOutline,
                                                            contentDescription = "Eliminar ajuste",
                                                            tint = MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(8.dp))

                                                // Chips showing the preset's lines in their preserved order
                                                LazyRow(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    items(preset.lines, key = { it.codLinea }) { pLine ->
                                                        Surface(
                                                            shape = RoundedCornerShape(16.dp),
                                                            color = MaterialTheme.colorScheme.surface,
                                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                        ) {
                                                            Text(
                                                                text = pLine.nombreCorto,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Subgroups and Lines List
                            val visibleCategories = if (state.selectedCategory == null) {
                                allCategories
                            } else {
                                allCategories.filter { it.name == state.selectedCategory }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .nestedScroll(noOverscrollConnection),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                visibleCategories.forEach { category ->
                                    // Direct lines in this category (e.g. TRONCALES)
                                    if (category.directLines.isNotEmpty()) {
                                        item(key = "direct_${category.name}") {
                                            SubgroupSectionHeader(
                                                title = category.name,
                                                count = category.directLines.size,
                                                isExpanded = true,
                                                onToggle = {}
                                            )
                                        }
                                        items(category.directLines, key = { "dir_${category.name}_${it.codLinea}" }) { entry ->
                                            val flatLine = FlatBusLine(
                                                groupPath = category.name,
                                                codLinea = entry.codLinea,
                                                descripcion = entry.descripcion
                                            )
                                            val isSelected = state.selectedLines.any { it.codLinea == flatLine.codLinea }
                                            val colorHex = state.activeLines[flatLine.codLinea]?.colorHex
                                            BusLineItem(
                                                line = flatLine,
                                                isSelected = isSelected,
                                                assignedColorHex = colorHex,
                                                onClick = { viewModel.toggleLineSelection(flatLine) }
                                            )
                                        }
                                    }

                                    // Subgroups inside category (e.g. Corredor 1, Corredor 2...)
                                    category.subgroups.forEach { subgroup ->
                                        val subKey = "${category.name}::${subgroup.subgroupName}"
                                        val isExpanded = state.expandedSubgroups.contains(subKey)

                                        item(key = "header_$subKey") {
                                            SubgroupSectionHeader(
                                                title = if (state.selectedCategory == null) "${category.name} • ${subgroup.subgroupName}" else subgroup.subgroupName,
                                                count = subgroup.lines.size,
                                                isExpanded = isExpanded,
                                                onToggle = { viewModel.toggleSubgroupExpanded(subKey) }
                                            )
                                        }

                                        if (isExpanded) {
                                            items(subgroup.lines, key = { "line_${subKey}_${it.codLinea}" }) { entry ->
                                                val flatLine = FlatBusLine(
                                                    groupPath = "${category.name} > ${subgroup.subgroupName}",
                                                    codLinea = entry.codLinea,
                                                    descripcion = entry.descripcion
                                                )
                                                val isSelected = state.selectedLines.any { it.codLinea == flatLine.codLinea }
                                                val colorHex = state.activeLines[flatLine.codLinea]?.colorHex
                                                BusLineItem(
                                                    line = flatLine,
                                                    isSelected = isSelected,
                                                    assignedColorHex = colorHex,
                                                    onClick = { viewModel.toggleLineSelection(flatLine) },
                                                    modifier = Modifier.padding(start = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSavePresetDialog) {
        SavePresetDialog(
            currentLinesCount = state.selectedLines.size,
            onDismiss = { showSavePresetDialog = false },
            onSave = { name -> viewModel.saveCurrentPreset(name) }
        )
    }

    if (presetToDelete != null) {
        val preset = presetToDelete!!
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text("Eliminar Ajuste") },
            text = {
                Text("¿Estás seguro de que querés eliminar el ajuste \"${preset.name}\"?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePreset(preset.id)
                        presetToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun SubgroupSectionHeader(
    title: String,
    count: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "$count líneas",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Colapsar" else "Expandir",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun BusLineItem(
    line: FlatBusLine,
    isSelected: Boolean,
    assignedColorHex: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lineAccentColor = if (assignedColorHex != null) {
        try {
            Color(android.graphics.Color.parseColor(assignedColorHex))
        } catch (_: Exception) {
            MaterialTheme.colorScheme.primary
        }
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        color = if (isSelected) lineAccentColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(1.5.dp, lineAccentColor) else null,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) lineAccentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.Check else Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = line.descripcion,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isSelected && assignedColorHex != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(lineAccentColor)
                        )
                    }
                }
                Text(
                    text = "${line.groupPath} • Línea ${line.codLinea}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SavePresetDialog(
    currentLinesCount: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Result<BusPreset>
) {
    var presetName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar Ajuste") },
        text = {
            Column {
                Text(
                    text = "Guardá la configuración actual ($currentLinesCount colectivos) para cargarla rápidamente más tarde.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = presetName,
                    onValueChange = {
                        presetName = it
                        errorMessage = null
                    },
                    label = { Text("Nombre del ajuste") },
                    placeholder = { Text("Ej: Casa al Trabajo, Facultad...") },
                    singleLine = true,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val result = onSave(presetName)
                    result.fold(
                        onSuccess = { onDismiss() },
                        onFailure = { errorMessage = it.message ?: "Error al guardar el ajuste" }
                    )
                },
                enabled = presetName.isNotBlank()
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
