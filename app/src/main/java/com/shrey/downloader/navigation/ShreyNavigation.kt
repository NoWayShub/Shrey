package com.shrey.downloader.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.shrey.downloader.core.design.ShreyAmbientBackground
import com.shrey.downloader.core.design.ShreyBottomBarDefaults
import com.shrey.downloader.core.design.ShreyGlassBottomBar
import com.shrey.downloader.core.design.ShreyNavDestination
import com.shrey.downloader.feature.about.AboutScreen
import com.shrey.downloader.feature.about.LicensesScreen
import com.shrey.downloader.feature.downloads.DownloadsScreen
import com.shrey.downloader.feature.downloads.DownloadsViewModel
import com.shrey.downloader.feature.home.HomeScreen
import com.shrey.downloader.feature.home.HomeViewModel
import com.shrey.downloader.feature.library.LibraryScreen
import com.shrey.downloader.feature.library.LibraryViewModel
import com.shrey.downloader.feature.settings.SettingsScreen
import com.shrey.downloader.feature.settings.SettingsViewModel
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object DownloadsRoute
@Serializable data object LibraryRoute
@Serializable data object SettingsRoute
@Serializable data object AboutRoute
@Serializable data object LicensesRoute

/**
 * Provides the exact, synchronously measured [Scaffold] bottom bar + navigation bar inset to all
 * top-level screens without capturing changing layout values inside [NavHost]'s graph builder lambda.
 */
val LocalShreyBottomBarPadding = compositionLocalOf { PaddingValues(0.dp) }

/**
 * Computes reliable scrollable bottom clearance that accounts for the floating bottom navigation
 * bar height, the Android system gesture / 3-button navigation bar inset, and comfortable visual
 * breathing room so no scrollable content is ever covered by the floating navigation bar.
 */
@Composable
fun rememberShreyBottomScrollClearance(
    contentBottomPadding: PaddingValues,
    extraSpacing: Dp = ShreyBottomBarDefaults.ExtraScrollClearance
): Dp {
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val baseBottom = maxOf(
        contentBottomPadding.calculateBottomPadding(),
        navBarBottom
    )
    return baseBottom + extraSpacing
}

@Composable
fun ShreyAppRoot(
    homeViewModel: HomeViewModel,
    downloadsViewModel: DownloadsViewModel,
    libraryViewModel: LibraryViewModel,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val downloadsState by downloadsViewModel.uiState.collectAsStateWithLifecycle()

    val selectedTabId = when {
        currentDestination?.hasRoute<DownloadsRoute>() == true -> "downloads"
        currentDestination?.hasRoute<LibraryRoute>() == true -> "library"
        currentDestination?.hasRoute<SettingsRoute>() == true ||
            currentDestination?.hasRoute<AboutRoute>() == true ||
            currentDestination?.hasRoute<LicensesRoute>() == true -> "settings"
        else -> "home"
    }

    val isTopLevelRoute = currentDestination == null ||
        currentDestination.hasRoute<HomeRoute>() ||
        currentDestination.hasRoute<DownloadsRoute>() ||
        currentDestination.hasRoute<LibraryRoute>() ||
        currentDestination.hasRoute<SettingsRoute>()

    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val showBottomBar = isTopLevelRoute && !isKeyboardVisible
    var measuredBottomBarHeightPx by remember { mutableIntStateOf(0) }

    val destinations = remember(downloadsState.inProgressCount) {
        listOf(
            ShreyNavDestination(
                id = "home",
                label = "Home",
                icon = Icons.Outlined.Home,
                selectedIcon = Icons.Filled.Home
            ),
            ShreyNavDestination(
                id = "downloads",
                label = "Downloads",
                icon = Icons.Outlined.Download,
                selectedIcon = Icons.Filled.Download,
                badgeCount = downloadsState.inProgressCount
            ),
            ShreyNavDestination(
                id = "library",
                label = "Library",
                icon = Icons.Outlined.VideoLibrary,
                selectedIcon = Icons.Filled.VideoLibrary
            ),
            ShreyNavDestination(
                id = "settings",
                label = "Settings",
                icon = Icons.Outlined.Settings,
                selectedIcon = Icons.Filled.Settings
            )
        )
    }

    val navigateTopLevel = remember(navController) {
        { tabId: String ->
            val route: Any = when (tabId) {
                "downloads" -> DownloadsRoute
                "library" -> LibraryRoute
                "settings" -> SettingsRoute
                else -> HomeRoute
            }
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    ShreyAmbientBackground(modifier = modifier) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            // Preserve top edge-to-edge drawing for the top gradual blur while always accounting
            // for bottom system navigation / gesture insets in Scaffold innerPadding.
            contentWindowInsets = WindowInsets.navigationBars,
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }
                ) {
                    ShreyGlassBottomBar(
                        destinations = destinations,
                        selectedId = selectedTabId,
                        onSelect = navigateTopLevel,
                        modifier = Modifier.onSizeChanged { size ->
                            if (size.height > 0) {
                                measuredBottomBarHeightPx = size.height
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val measuredBottomBarHeight = with(density) { measuredBottomBarHeightPx.toDp() }
            val scaffoldBottom = innerPadding.calculateBottomPadding()
            val resolvedBottomPadding = if (showBottomBar) {
                maxOf(
                    scaffoldBottom,
                    measuredBottomBarHeight,
                    ShreyBottomBarDefaults.MinBarHeight + navBarBottom
                )
            } else {
                maxOf(scaffoldBottom, navBarBottom)
            }
            val effectiveBottomPadding = remember(resolvedBottomPadding) {
                PaddingValues(bottom = resolvedBottomPadding)
            }
            CompositionLocalProvider(LocalShreyBottomBarPadding provides effectiveBottomPadding) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = HomeRoute,
                        enterTransition = { fadeIn() },
                        exitTransition = { fadeOut() }
                    ) {
                        composable<HomeRoute> {
                            HomeScreen(
                                viewModel = homeViewModel,
                                onOpenDownloads = { navigateTopLevel("downloads") },
                                onOpenSettings = { navigateTopLevel("settings") }
                            )
                        }

                        composable<DownloadsRoute> {
                            DownloadsScreen(
                                viewModel = downloadsViewModel
                            )
                        }

                        composable<LibraryRoute> {
                            LibraryScreen(
                                viewModel = libraryViewModel
                            )
                        }

                        composable<SettingsRoute> {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onOpenAbout = { navController.navigate(AboutRoute) },
                                onOpenLicenses = { navController.navigate(LicensesRoute) }
                            )
                        }

                        composable<AboutRoute> {
                            AboutScreen(onBack = { navController.popBackStack() })
                        }

                        composable<LicensesRoute> {
                            LicensesScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
