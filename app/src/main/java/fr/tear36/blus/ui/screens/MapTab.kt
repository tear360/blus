package fr.tear36.blus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.tear36.blus.data.FeedState
import fr.tear36.blus.data.Route
import fr.tear36.blus.data.ShapePoint
import fr.tear36.blus.data.Stop
import fr.tear36.blus.data.haversineMeters
import fr.tear36.blus.ui.BlusViewModel
import fr.tear36.blus.ui.LatLon
import fr.tear36.blus.ui.map.BlusMap
import fr.tear36.blus.ui.map.MapData
import fr.tear36.blus.ui.map.NANTES_CENTER

private enum class Tab(val label: String, val icon: ImageVector) {
    MAP("Carte", Icons.Default.Map),
    NEARBY("À proximité", Icons.Default.DirectionsBus),
    TRAFFIC("Trafic", Icons.Default.Traffic),
    SETTINGS("Réglages", Icons.Default.Settings),
}

@Composable
fun MapTab(vm: BlusViewModel, onRequestLocation: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.MAP) }
    val feed by vm.feedState.collectAsState()
    val realtime by vm.realtime.collectAsState()
    val routes by vm.routes.collectAsState()
    val mapShapes by vm.mapShapes.collectAsState()
    val visibleRouteIds by vm.visibleRouteIds.collectAsState()
    val selectedStopId by vm.selectedStopId.collectAsState()
    val location by vm.location.collectAsState()

    var showLineFilter by remember { mutableStateOf(false) }
    var mapStops by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var origin by remember { mutableStateOf<Stop?>(null) }

    // Refresh the set of stops drawn around the current position.
    LaunchedEffect(location, feed, selectedStopId) {
        if (feed is FeedState.Ready || feed is FeedState.Idle) {
            origin = vm.nearestStation()
            mapStops = vm.nearbyStops(1600, 120)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (feed !is FeedState.Ready) {
                FeedProgressBar(feed)
            }

            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.MAP -> BlusMap(
                        data = MapData(
                            vehicles = realtime.vehicles,
                            stops = mapStops,
                            routes = routes,
                            shapes = mapShapes,
                            visibleRouteIds = visibleRouteIds ?: mapShapes.keys,
                            selectedStopId = selectedStopId,
                            center = location?.let { LatLon(it.lat, it.lon) },
                            onStopClick = { vm.selectedStopId.value = it.id },
                            onVehicleClick = { v ->
                                v.nextStopId?.let { vm.selectedStopId.value = it }
                            },
                        ),
                        onRecenter = {
                            if (location != null) {
                                vm.pushLocation(location!!.lat, location!!.lon)
                            } else {
                                onRequestLocation()
                                vm.pushLocation(NANTES_CENTER.latitude, NANTES_CENTER.longitude)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    Tab.NEARBY -> NearbyScreen(vm, Modifier.fillMaxSize())
                    Tab.TRAFFIC -> TrafficScreen(vm, Modifier.fillMaxSize())
                    Tab.SETTINGS -> SettingsScreen(vm, Modifier.fillMaxSize())
                }

                if (tab == Tab.MAP && feed is FeedState.Ready && mapShapes.isNotEmpty()) {
                    IconButton(
                        onClick = { showLineFilter = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                MaterialTheme.shapes.small,
                            ),
                    ) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "Choisir les lignes affichées",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        if (showLineFilter) {
            LineFilterSheet(
                routes = routes,
                shapes = mapShapes,
                visible = visibleRouteIds ?: mapShapes.keys,
                onToggle = vm::toggleRouteVisible,
                onSetAll = vm::setAllRoutesVisible,
                onDismiss = { showLineFilter = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineFilterSheet(
    routes: Map<String, Route>,
    shapes: Map<String, List<ShapePoint>>,
    visible: Set<String>,
    onToggle: (String) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ordered = remember(routes, shapes) {
        shapes.keys
            .mapNotNull { routes[it] }
            .sortedWith(compareBy({ it.sortOrder }, { it.shortName }))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Lignes affichées",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSetAll(visible.size != ordered.size) }) {
                Text(if (visible.size == ordered.size) "Tout masquer" else "Tout afficher")
            }
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            items(ordered, key = { it.id }) { route ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(route.id) }
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = route.id in visible, onCheckedChange = { onToggle(route.id) })
                    RouteBadge(route, size = 32)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = route.longName.ifBlank { route.shortName },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FeedProgressBar(state: FeedState) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (state) {
                        is FeedState.Downloading -> "Téléchargement du réseau Naolib (27 Mo)…"
                        is FeedState.Importing -> "Construction de la base locale (${state.file})…"
                        is FeedState.Failed -> "Réseau indisponible : ${state.message}"
                        FeedState.Idle -> "Initialisation…"
                        is FeedState.Ready -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state is FeedState.Ready) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            when (state) {
                is FeedState.Downloading -> LinearProgressIndicator(
                    progress = {
                        if (state.total > 0) (state.bytesRead.toFloat() / state.total).coerceIn(0f, 1f) else 0.02f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                is FeedState.Importing -> LinearProgressIndicator(
                    progress = { state.fraction.coerceAtLeast(0.02f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                is FeedState.Failed -> LinearProgressIndicator(
                    progress = { 1f },
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Unit
            }
        }
    }
}