package fr.tear36.blus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.tear36.blus.data.Route
import fr.tear36.blus.data.RouteVariant
import fr.tear36.blus.data.Stop
import fr.tear36.blus.data.TransitRepository.Departure
import fr.tear36.blus.data.haversineMeters
import fr.tear36.blus.ui.BlusViewModel

private enum class NearbyMode(val label: String) {
    NEARBY("Autour de moi"),
    LINES("Par ligne"),
    FAVORITES("Favoris"),
}

private data class NearbyRow(
    val stop: Stop,
    val distanceMeters: Double?,
    val routes: List<Route>,
    val departures: List<Departure>,
)

private data class DirectionRow(
    val variant: RouteVariant,
    val stops: List<Stop>,
)

@Composable
fun NearbyScreen(vm: BlusViewModel, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(NearbyMode.NEARBY) }
    var selectedRouteId by remember { mutableStateOf<String?>(null) }

    var origin by remember { mutableStateOf<Stop?>(null) }
    var rows by remember { mutableStateOf<List<NearbyRow>>(emptyList()) }
    var directions by remember { mutableStateOf<List<DirectionRow>>(emptyList()) }
    var favoriteRows by remember { mutableStateOf<List<NearbyRow>>(emptyList()) }

    val favorites by vm.favorites.collectAsState()
    val routes by vm.routes.collectAsState()
    val tick = vm.realtime.value.fetchedAt

    // Recompute when the feed ticks (20 s) or the query changes.
    LaunchedEffect(vm.location.value, tick, query) {
        origin = vm.nearestStation()
        val base = origin
        rows = if (query.length >= 2) {
            vm.searchNow(query)
        } else {
            vm.nearbyStops(1200, 25)
        }.map { stop ->
            NearbyRow(
                stop = stop,
                distanceMeters = base?.let { haversineMeters(stop.lat, stop.lon, it.lat, it.lon) },
                routes = vm.routesAt(stop.id).take(6),
                departures = vm.departures(stop.id, 4),
            )
        }
    }

    // Stops of the selected line, one section per direction.
    LaunchedEffect(selectedRouteId, tick) {
        val id = selectedRouteId
        directions = if (id == null) {
            emptyList()
        } else {
            vm.routeVariants(id).map { variant ->
                DirectionRow(variant, vm.routeDirectionStops(id, variant))
            }
        }
    }

    LaunchedEffect(favorites, tick, vm.location.value) {
        val base = origin ?: vm.nearestStation()
        favoriteRows = vm.favoriteStops().map { stop ->
            NearbyRow(
                stop = stop,
                distanceMeters = base?.let { haversineMeters(stop.lat, stop.lon, it.lat, it.lon) },
                routes = vm.routesAt(stop.id).take(6),
                departures = vm.departures(stop.id, 4),
            )
        }.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
    }

    val now = System.currentTimeMillis()
    val searching = query.length >= 2

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.isNotBlank()) mode = NearbyMode.NEARBY
                },
                placeholder = { Text("Rechercher un arrêt") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { vm.refreshRealtimeNow() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Rafraîchir",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (selectedRouteId != null) {
            RouteStopsHeader(
                route = routes[selectedRouteId],
                onBack = { selectedRouteId = null },
            )
        } else {
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                NearbyMode.entries.forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = {
                            mode = m
                            if (m != NearbyMode.LINES) selectedRouteId = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, NearbyMode.entries.size),
                    ) {
                        Text(m.label, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.size(6.dp))

        when {
            selectedRouteId != null -> RouteStopsList(
                route = routes[selectedRouteId],
                directions = directions,
                favorites = favorites,
                onToggleFavorite = { vm.toggleFavorite(it.id) },
            )

            mode == NearbyMode.LINES -> LinePicker(
                routes = routes,
                onPick = { selectedRouteId = it },
            )

            mode == NearbyMode.FAVORITES -> FavoriteList(
                rows = favoriteRows,
                favorites = favorites,
                now = now,
                routes = routes,
                onToggleFavorite = { vm.toggleFavorite(it) },
                onSelect = {
                    vm.selectedStopId.value = it.stop.id
                    vm.pushLocation(it.stop.lat, it.stop.lon)
                },
            )

            rows.isEmpty() -> EmptyHint(
                text = if (searching) {
                    "Aucun arrêt ne correspond à « $query »."
                } else {
                    "Aucun arrêt autour de vous. Vérifiez la connexion dans Réglages."
                },
            )

            else -> StopList(
                rows = rows,
                favorites = favorites,
                now = now,
                routes = routes,
                header = if (searching) {
                    "${rows.size} résultat(s)"
                } else {
                    "Autour de ${origin?.name ?: "vous"}"
                },
                onToggleFavorite = { vm.toggleFavorite(it) },
                onSelect = {
                    vm.selectedStopId.value = it.stop.id
                    vm.pushLocation(it.stop.lat, it.stop.lon)
                },
            )
        }
    }
}

@Composable
private fun RouteStopsHeader(route: Route?, onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
            }
            RouteBadge(route, size = 32)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = route?.longName.orEmpty().ifBlank { route?.shortName.orEmpty() },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LinePicker(
    routes: Map<String, Route>,
    onPick: (String) -> Unit,
) {
    val ordered = remember(routes) {
        routes.values.sortedWith(compareBy({ it.sortOrder }, { it.shortName }))
    }
    if (ordered.isEmpty()) {
        EmptyHint("Chargement des lignes…")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("${ordered.size} lignes") }
        items(ordered, key = { it.id }) { route ->
            Surface(
                onClick = { onPick(route.id) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RouteBadge(route, size = 34)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = route.longName.ifBlank { route.shortName },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item { Spacer(Modifier.size(96.dp)) }
    }
}

@Composable
private fun RouteStopsList(
    route: Route?,
    directions: List<DirectionRow>,
    favorites: Set<String>,
    onToggleFavorite: (Stop) -> Unit,
) {
    if (directions.isEmpty()) {
        EmptyHint("Chargement de l’itinéraire…")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        directions.forEach { dir ->
            item {
                SectionHeader(
                    "Sens ${dir.variant.directionId + 1}" +
                        dir.variant.headsign.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
                )
            }
            items(dir.stops, key = { "${dir.variant.tripId}|${it.id}" }) { stop ->
                StopRow(
                    stop = stop,
                    distanceMeters = null,
                    routes = listOfNotNull(route),
                    isFavorite = stop.id in favorites,
                    onClick = {},
                    onToggleFavorite = { onToggleFavorite(stop) },
                )
            }
        }
        item { Spacer(Modifier.size(96.dp)) }
    }
}

@Composable
private fun FavoriteList(
    rows: List<NearbyRow>,
    favorites: Set<String>,
    now: Long,
    routes: Map<String, Route>,
    onToggleFavorite: (String) -> Unit,
    onSelect: (NearbyRow) -> Unit,
) {
    if (rows.isEmpty()) {
        EmptyHint(
            "Aucun favori. Touchez l’étoile d’un arrêt pour le retrouver ici et " +
                "recevoir une alerte quand le bus approche.",
        )
        return
    }
    StopList(
        rows = rows,
        favorites = favorites,
        now = now,
        routes = routes,
        header = "${rows.size} favori(s)",
        onToggleFavorite = onToggleFavorite,
        onSelect = onSelect,
    )
}

@Composable
private fun StopList(
    rows: List<NearbyRow>,
    favorites: Set<String>,
    now: Long,
    header: String,
    onToggleFavorite: (String) -> Unit,
    onSelect: (NearbyRow) -> Unit,
    routes: Map<String, Route>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item { SectionHeader(header) }
        items(rows, key = { it.stop.id }) { row ->
            StopRow(
                stop = row.stop,
                distanceMeters = row.distanceMeters,
                routes = row.routes,
                isFavorite = row.stop.id in favorites,
                onClick = { onSelect(row) },
                onToggleFavorite = { onToggleFavorite(row.stop.id) },
            ) {
                if (row.departures.isEmpty()) {
                    Text(
                        "Aucun passage annoncé",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    row.departures.forEach { d ->
                        DepartureRow(departure = d, route = routes[d.routeId], now = now)
                    }
                }
            }
        }
        item { Spacer(Modifier.size(96.dp)) }
    }
}
