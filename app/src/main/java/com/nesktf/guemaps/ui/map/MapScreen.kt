package com.nesktf.guemaps.ui.map

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

    Box(modifier = modifier.fillMaxSize()) {
        // Map View
        OsmMapView(
            routeNodes = state.routeNodes,
            activeBuses = state.activeBuses,
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

            // Line Selector Floating Card
            Surface(
                onClick = { viewModel.setLinePickerOpen(true) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsBus,
                        contentDescription = "Línea",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.selectedLine?.descripcion ?: "Seleccionar Línea",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.selectedLine?.groupPath ?: "Toque aquí para buscar o elegir línea",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (state.isLoadingRoute) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        // Floating Popup on Top-Left Corner (Speed and User-Relative Stop Distance)
        if (state.selectedLine != null && state.activeBuses.isNotEmpty()) {
            val focusedInterno = state.selectedBusInterno ?: state.activeBuses.firstOrNull()?.interno
            val liveDetail = focusedInterno?.let { state.busLiveDetails[it] }

            if (liveDetail != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 5.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = if (state.isGroupsOffline || state.isRouteOffline) 130.dp else 90.dp, start = 14.dp)
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
            if (state.selectedLine != null) {
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
            if (state.selectedLine != null && !state.isLoadingRoute) {
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
                        Text(
                            text = when {
                                state.isLoadingBuses -> "Actualizando posiciones..."
                                state.busesErrorMessage != null -> state.busesErrorMessage ?: ""
                                totalBuses == 0 -> "No hay colectivos activos en este momento"
                                totalBuses == 1 -> {
                                    if (rampBuses == 1) "1 colectivo activo (con rampa ♿)"
                                    else "1 colectivo activo (sin rampa)"
                                }
                                else -> "$totalBuses colectivos activos ($rampBuses con rampa ♿)"
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
                    Text(
                        text = "Seleccionar Línea",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

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
                                val isFav = state.favoriteLineCodes.contains(line.codLinea)
                                BusLineItem(
                                    line = line,
                                    isSelected = state.selectedLine?.codLinea == line.codLinea,
                                    isFavorite = isFav,
                                    onToggleFavorite = { viewModel.toggleFavoriteLine(line) },
                                    onClick = { viewModel.selectLine(line) }
                                )
                            }
                        }
                    } else {
                        // Display hierarchical Subgroup view or Favorites!
                        // 1. Horizontal Category Chips
                        val allCategories = state.categories
                        val hasFavorites = state.favoriteLines.isNotEmpty()
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            if (hasFavorites) {
                                item {
                                    FilterChip(
                                        selected = state.selectedCategory == "FAVORITOS",
                                        onClick = {
                                            viewModel.selectCategory(
                                                if (state.selectedCategory == "FAVORITOS") null else "FAVORITOS"
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = null,
                                                tint = Color(0xFFF59E0B),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        label = { Text("Favoritos (${state.favoriteLines.size})") }
                                    )
                                }
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

                        if (state.selectedCategory == "FAVORITOS") {
                            // 2. Favorites List
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .nestedScroll(noOverscrollConnection),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(state.favoriteLines, key = { "fav_${it.codLinea}" }) { line ->
                                    BusLineItem(
                                        line = line,
                                        isSelected = state.selectedLine?.codLinea == line.codLinea,
                                        isFavorite = true,
                                        onToggleFavorite = { viewModel.toggleFavoriteLine(line) },
                                        onClick = { viewModel.selectLine(line) }
                                    )
                                }
                            }
                        } else {
                            // 3. Subgroups and Lines List
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
                                            val isFav = state.favoriteLineCodes.contains(flatLine.codLinea)
                                            BusLineItem(
                                                line = flatLine,
                                                isSelected = state.selectedLine?.codLinea == flatLine.codLinea,
                                                isFavorite = isFav,
                                                onToggleFavorite = { viewModel.toggleFavoriteLine(flatLine) },
                                                onClick = { viewModel.selectLine(flatLine) }
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
                                                val isFav = state.favoriteLineCodes.contains(flatLine.codLinea)
                                                BusLineItem(
                                                    line = flatLine,
                                                    isSelected = state.selectedLine?.codLinea == flatLine.codLinea,
                                                    isFavorite = isFav,
                                                    onToggleFavorite = { viewModel.toggleFavoriteLine(flatLine) },
                                                    onClick = { viewModel.selectLine(flatLine) },
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
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
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
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = line.descripcion,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${line.groupPath} • Línea ${line.codLinea}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isFavorite) "Quitar de favoritos" else "Agregar a favoritos",
                    tint = if (isFavorite) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
