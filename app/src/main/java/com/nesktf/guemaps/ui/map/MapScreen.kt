package com.nesktf.guemaps.ui.map

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nesktf.guemaps.data.model.BusEntry
import com.nesktf.guemaps.data.model.FlatBusLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = modifier.fillMaxSize()) {
        // Map View
        OsmMapView(
            routeNodes = state.routeNodes,
            activeBuses = state.activeBuses,
            showStops = state.showStops,
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
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { viewModel.setLinePickerOpen(true) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsBus,
                            contentDescription = "Bus",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.selectedLine?.let { "${it.descripcion} (${it.codLinea})" }
                                ?: "Seleccionar colectivo",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = state.selectedLine?.groupPath
                                ?: "Toque para ver las líneas y grupos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // Status bar for active buses
            if (state.selectedLine != null && !state.isLoadingRoute) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                state.isLoadingBuses -> "Actualizando posiciones..."
                                state.busesErrorMessage != null -> state.busesErrorMessage ?: ""
                                state.activeBuses.isEmpty() -> "No hay colectivos activos en este momento"
                                state.activeBuses.size == 1 -> "1 colectivo activo en el mapa"
                                else -> "${state.activeBuses.size} colectivos activos en el mapa"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        // Floating Action Buttons
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Toggle Stops
            FloatingActionButton(
                onClick = { viewModel.toggleStops() },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = if (state.showStops) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier.size(48.dp)
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
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(state.filteredLines) { line ->
                                BusLineItem(
                                    line = line,
                                    isSelected = state.selectedLine?.codLinea == line.codLinea,
                                    onClick = { viewModel.selectLine(line) }
                                )
                            }
                        }
                    } else {
                        // Display hierarchical Subgroup view!
                        // 1. Horizontal Category Chips
                        val allCategories = state.categories
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
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

                        // 2. Subgroups and Lines List
                        val visibleCategories = if (state.selectedCategory == null) {
                            allCategories
                        } else {
                            allCategories.filter { it.name == state.selectedCategory }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
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
                                        BusLineItem(
                                            line = flatLine,
                                            isSelected = state.selectedLine?.codLinea == flatLine.codLinea,
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
                                            BusLineItem(
                                                line = flatLine,
                                                isSelected = state.selectedLine?.codLinea == flatLine.codLinea,
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
        }
    }
}
