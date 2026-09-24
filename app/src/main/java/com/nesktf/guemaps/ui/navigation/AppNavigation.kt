package com.nesktf.guemaps.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nesktf.guemaps.R
import com.nesktf.guemaps.ui.about.AboutScreen
import com.nesktf.guemaps.ui.balance.BalanceScreen
import com.nesktf.guemaps.ui.balance.BalanceViewModel
import com.nesktf.guemaps.ui.map.MapScreen
import com.nesktf.guemaps.ui.map.MapViewModel
import com.nesktf.guemaps.ui.news.NewsScreen
import com.nesktf.guemaps.ui.news.NewsViewModel
import com.nesktf.guemaps.ui.notice.NoticeDialog
import com.nesktf.guemaps.ui.notice.NoticePreferences
import com.nesktf.guemaps.ui.sellingpoints.SellingPointsScreen
import com.nesktf.guemaps.ui.sellingpoints.SellingPointsViewModel
import com.nesktf.guemaps.ui.splash.SplashScreen
import kotlinx.coroutines.delay

enum class AppDestination {
    MAP,
    SELLING_POINTS,
    BALANCE,
    NEWS
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isSplashFinished by rememberSaveable { mutableStateOf(false) }
    var currentSplashTask by remember { mutableStateOf(context.getString(R.string.splash_task_init)) }

    // Smooth startup tasks simulation coordinating ViewModel loading
    LaunchedEffect(Unit) {
        if (!isSplashFinished) {
            currentSplashTask = context.getString(R.string.splash_task_init)
            delay(280)
            currentSplashTask = context.getString(R.string.splash_task_routes)
            delay(420)
            currentSplashTask = context.getString(R.string.splash_task_selling_points)
            delay(350)
            currentSplashTask = context.getString(R.string.splash_task_news)
            delay(350)
            currentSplashTask = context.getString(R.string.splash_task_ready)
            delay(180)
            isSplashFinished = true
        }
    }

    Crossfade(targetState = isSplashFinished, label = "AppSplashCrossfade") { ready ->
        if (!ready) {
            SplashScreen(currentTaskText = currentSplashTask, modifier = modifier)
        } else {
            MainAppContent(modifier = modifier)
        }
    }
}

@Composable
private fun MainAppContent(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Shared or scoped ViewModels initialized when main content is displayed
    val mapViewModel: MapViewModel = viewModel()
    val sellingPointsViewModel: SellingPointsViewModel = viewModel()
    val balanceViewModel: BalanceViewModel = viewModel()
    val newsViewModel: NewsViewModel = viewModel()

    val unreadNewsCount by newsViewModel.unreadCount.collectAsState()

    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.MAP) }
    var showAboutScreen by rememberSaveable { mutableStateOf(false) }
    var showNoticeDialog by rememberSaveable {
        mutableStateOf(!NoticePreferences.isNoticeDisabled(context))
    }

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
                                    Icon(
                                        Icons.Default.Map,
                                        contentDescription = stringResource(id = R.string.nav_routes)
                                    )
                                },
                                label = { Text(stringResource(id = R.string.nav_routes)) }
                            )
                            NavigationBarItem(
                                selected = currentDestination == AppDestination.SELLING_POINTS,
                                onClick = { currentDestination = AppDestination.SELLING_POINTS },
                                icon = {
                                    Icon(
                                        Icons.Default.Storefront,
                                        contentDescription = stringResource(id = R.string.nav_selling_points)
                                    )
                                },
                                label = { Text(stringResource(id = R.string.nav_selling_points)) }
                            )
                            NavigationBarItem(
                                selected = currentDestination == AppDestination.BALANCE,
                                onClick = { currentDestination = AppDestination.BALANCE },
                                icon = {
                                    Icon(
                                        Icons.Default.CreditCard,
                                        contentDescription = stringResource(id = R.string.nav_balance)
                                    )
                                },
                                label = { Text(stringResource(id = R.string.nav_balance)) }
                            )
                            NavigationBarItem(
                                selected = currentDestination == AppDestination.NEWS,
                                onClick = { currentDestination = AppDestination.NEWS },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (unreadNewsCount > 0) {
                                                Badge {
                                                    Text(if (unreadNewsCount > 9) "9+" else unreadNewsCount.toString())
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Article,
                                            contentDescription = stringResource(id = R.string.nav_news)
                                        )
                                    }
                                },
                                label = { Text(stringResource(id = R.string.nav_news)) }
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
                        AppDestination.NEWS -> {
                            NewsScreen(
                                viewModel = newsViewModel,
                                onOpenAbout = { showAboutScreen = true },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
        }

        if (showNoticeDialog) {
            NoticeDialog(
                onDismiss = { dontShowAgain ->
                    if (dontShowAgain) {
                        NoticePreferences.setNoticeDisabled(context, true)
                    }
                    showNoticeDialog = false
                }
            )
        }
    }

