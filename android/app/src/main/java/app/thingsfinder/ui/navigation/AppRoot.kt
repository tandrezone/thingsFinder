package app.thingsfinder.ui.navigation

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.thingsfinder.R
import app.thingsfinder.ThingsFinderApp
import app.thingsfinder.domain.AppLink
import app.thingsfinder.domain.BoxLinks
import app.thingsfinder.domain.LinkAction
import app.thingsfinder.domain.LinkTarget
import app.thingsfinder.platform.CodeScanner
import app.thingsfinder.platform.ScanResult
import app.thingsfinder.ui.barcodes.BarcodesScreen
import app.thingsfinder.ui.box.BoxDetailScreen
import app.thingsfinder.ui.groups.GroupsScreen
import app.thingsfinder.ui.place.PlaceDetailScreen
import app.thingsfinder.ui.places.PlacesScreen
import app.thingsfinder.ui.search.SearchScreen
import app.thingsfinder.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private enum class TopLevel(val route: Any, val label: Int, val selected: ImageVector, val unselected: ImageVector) {
    Places(PlacesRoute, R.string.nav_places, Icons.Filled.Home, Icons.Outlined.Home),
    Search(SearchRoute, R.string.nav_search, Icons.Filled.Search, Icons.Outlined.Search),
    Barcodes(BarcodesRoute, R.string.nav_barcodes, Icons.Filled.ViewWeek, Icons.Outlined.ViewWeek),
    Settings(SettingsRoute, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

private fun NavDestination?.topLevel(): TopLevel = when {
    this == null -> TopLevel.Places
    hasRoute<SearchRoute>() -> TopLevel.Search
    hasRoute<BarcodesRoute>() -> TopLevel.Barcodes
    hasRoute<SettingsRoute>() || hasRoute<GroupsRoute>() -> TopLevel.Settings
    else -> TopLevel.Places // places, a place, or a box
}

/**
 * Single-activity root. NavigationSuiteScaffold shows a bottom bar on
 * phones and a navigation rail on tablets / landscape, picked from the
 * window size class (PHP's top bar links: home, search, barcodes, account menu).
 */
@Composable
fun AppRoot(pendingLink: AppLink?, onLinkHandled: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination.topLevel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inventory = (context.applicationContext as ThingsFinderApp).container.inventory
    val boxNotFound = stringResource(R.string.msg_box_link_unknown)
    val placeNotFound = stringResource(R.string.msg_place_link_unknown)
    val notFound = stringResource(R.string.msg_link_unknown)
    val notABox = stringResource(R.string.msg_not_a_box_code)

    /** Box and place links open that screen (add / remove: with the sheet up or in remove mode); invites open Groups. */
    suspend fun openLink(link: AppLink) {
        when (link) {
            is AppLink.JoinGroup -> nav.navigate(GroupsRoute(link.token)) { launchSingleTop = true }
            is AppLink.Container -> {
                val token = link.target.token
                val box = if (link.target is LinkTarget.Place) null else inventory.findBoxByToken(token)
                val place = if (box != null || link.target is LinkTarget.Box) null else inventory.findPlaceByToken(token)
                val action = link.action.segment
                when {
                    box != null -> nav.navigate(BoxRoute(box.id, action)) { launchSingleTop = action == null }
                    place != null -> nav.navigate(PlaceRoute(place.id, action)) { launchSingleTop = action == null }
                    else -> {
                        val message = when (link.target) {
                            is LinkTarget.Box -> boxNotFound
                            is LinkTarget.Place -> placeNotFound
                            is LinkTarget.Either -> notFound
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // thingsfinder://box|place|join/... opened from the system camera or another app.
    LaunchedEffect(pendingLink) {
        if (pendingLink != null) {
            openLink(pendingLink)
            onLinkHandled()
        }
    }

    val scanBox: () -> Unit = {
        scope.launch {
            when (val r = CodeScanner.scanQr(context)) {
                is ScanResult.Scanned -> {
                    val link = BoxLinks.parse(r.value)
                    if (link == null) Toast.makeText(context, notABox, Toast.LENGTH_LONG).show() else openLink(link)
                }
                is ScanResult.Failed -> Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                ScanResult.Cancelled -> Unit
            }
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevel.entries.forEach { dest ->
                val selected = dest == current
                item(
                    selected = selected,
                    onClick = { nav.navigateTopLevel(dest.route, reselect = selected) },
                    icon = { Icon(if (selected) dest.selected else dest.unselected, contentDescription = null) },
                    label = { Text(stringResource(dest.label)) },
                )
            }
        },
    ) {
        NavHost(navController = nav, startDestination = PlacesRoute) {
            composable<PlacesRoute> {
                PlacesScreen(onOpenPlace = { nav.navigate(PlaceRoute(it)) }, onScanBox = scanBox)
            }
            composable<PlaceRoute> { entry ->
                val route = entry.toRoute<PlaceRoute>()
                PlaceDetailScreen(
                    placeId = route.placeId,
                    onBack = { nav.popBackStack() },
                    onOpenBox = { nav.navigate(BoxRoute(it)) },
                    initialAction = LinkAction.fromSegment(route.action),
                )
            }
            composable<BoxRoute> { entry ->
                val route = entry.toRoute<BoxRoute>()
                BoxDetailScreen(
                    boxId = route.boxId,
                    onBack = { nav.popBackStack() },
                    onOpenPlace = { placeId ->
                        // Going "up" to the box's place: reuse it if it's right below us on the stack.
                        val previous = nav.previousBackStackEntry
                        if (previous?.destination?.hasRoute<PlaceRoute>() == true && previous.toRoute<PlaceRoute>().placeId == placeId) {
                            nav.popBackStack()
                        } else {
                            nav.navigate(PlaceRoute(placeId))
                        }
                    },
                    initialAction = LinkAction.fromSegment(route.action),
                )
            }
            composable<SearchRoute> {
                SearchScreen(onOpenPlace = { nav.navigate(PlaceRoute(it)) }, onOpenBox = { nav.navigate(BoxRoute(it)) })
            }
            composable<BarcodesRoute> { BarcodesScreen() }
            composable<SettingsRoute> { SettingsScreen(onOpenGroups = { nav.navigate(GroupsRoute()) }) }
            composable<GroupsRoute> { entry ->
                GroupsScreen(joinToken = entry.toRoute<GroupsRoute>().joinToken, onBack = { nav.popBackStack() })
            }
        }
    }
}

/** Standard top-level tab navigation; tapping the current tab again returns to its root. */
private fun NavHostController.navigateTopLevel(route: Any, reselect: Boolean) {
    if (reselect) {
        popBackStack(route, inclusive = false)
        return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
