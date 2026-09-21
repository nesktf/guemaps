package com.nesktf.guemaps.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nesktf.guemaps.ui.about.AboutScreen
import com.nesktf.guemaps.ui.balance.BalanceScreen
import com.nesktf.guemaps.ui.balance.BalanceViewModel
import com.nesktf.guemaps.ui.map.MapScreen
import com.nesktf.guemaps.ui.map.MapViewModel
import com.nesktf.guemaps.ui.sellingpoints.SellingPointsScreen
import com.nesktf.guemaps.ui.sellingpoints.SellingPointsViewModel

enum class AppDestination(val label: String) {
    MAP("Recorridos"),
    SELLING_POINTS("Puntos de venta"),
    BALANCE("Saldo")
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.MAP) }
    var showAboutScreen by rememberSaveable { mutableStateOf(false) }

    // Shared or scoped ViewModels
    val mapViewModel: MapViewModel = viewModel()
    val sellingPointsViewModel: SellingPointsViewModel = viewModel()
    val balanceViewModel: BalanceViewModel = viewModel()

    if (showAboutScreen) {
        AboutScreen(onBack = { showAboutScreen = false })
    } else {
        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                ) {}
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentDestination == AppDestination.MAP,
                        onClick = { currentDestination = AppDestination.MAP },
                        icon = {
                            Icon(Icons.Default.Map, contentDescription = "Recorridos")
                        },
                        label = { Text("Recorridos") }
                    )
                    NavigationBarItem(
                        selected = currentDestination == AppDestination.SELLING_POINTS,
                        onClick = { currentDestination = AppDestination.SELLING_POINTS },
                        icon = {
                            Icon(Icons.Default.Storefront, contentDescription = "Puntos de venta")
                        },
                        label = { Text("Puntos de venta") }
                    )
                    NavigationBarItem(
                        selected = currentDestination == AppDestination.BALANCE,
                        onClick = { currentDestination = AppDestination.BALANCE },
                        icon = {
                            Icon(Icons.Default.CreditCard, contentDescription = "Saldo")
                        },
                        label = { Text("Saldo") }
                    )
                }
            },
            modifier = modifier.fillMaxSize()
        ) { innerPadding ->
            when (currentDestination) {
                AppDestination.MAP -> {
                    MapScreen(
                        viewModel = mapViewModel,
                        onOpenAbout = { showAboutScreen = true },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
                AppDestination.SELLING_POINTS -> {
                    SellingPointsScreen(
                        viewModel = sellingPointsViewModel,
                        onOpenAbout = { showAboutScreen = true },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
                AppDestination.BALANCE -> {
                    BalanceScreen(
                        viewModel = balanceViewModel,
                        onOpenAbout = { showAboutScreen = true },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
