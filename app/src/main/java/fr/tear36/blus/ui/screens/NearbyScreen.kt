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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import fr.tear36.blus.data.Route
import fr.tear36.blus.data.Stop
import fr.tear36.blus.data.TransitRepository.Departure
import fr.tear36.blus.data.haversineMeters
import fr.tear36.blus.ui.BlusViewModel

private data class NearbyRow(
    val stop: Stop,
    val distanceMeters: Double?,
    val routes: List<Route>,
    val departures: List<Departure>,
)

@Composable
fun NearbyScreen(vm: BlusViewModel, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf<Stop?>(null) }
    var searchHits by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var rows by remember { mutableStateOf<List<NearbyRow>>(emptyList()) }

    val favorites by vm.favorites.collectAsState()
    val routes by vm.routes.collectAsState()

    // Recompute when the feed ticks (20 s) or the query changes.
    LaunchedEffect(vm.location.value, vm.realtime.value.fetchedAt, query) {
        origin = vm.nearestStation()
        if (query.length >= 2) {
            val hits = vm.searchNow(query)
            searchHits = hits
            rows = hits.map { stop ->
                NearbyRow(
                    stop = stop,
                    distanceMeters = origin?.let { haversineMeters(stop.lat, stop.lon, it.lat, it.lon) },
                    routes = vm.routesAt(stop.id).take(6),
                    departures = vm.departures(stop.id, 3),
                )
            }
        } else {
            searchHits = emptyList()
            rows = vm.nearbyStops(1200, 25).map { stop ->
                NearbyRow(
                    stop = stop,
                    distanceMeters = haversineMeters(stop.lat, stop.lon, origin!!.lat, origin!!.lon),
                    routes = vm.routesAt(stop.id).take(6),
                    departures = vm.departures(stop.id, 3),
                )
            }
        }
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
                onValueChange = { query = it },
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

        if (rows.isEmpty()) {
            EmptyHint(
                text = if (searching) {
                    "Aucun arrêt ne correspond à « $query »."
                } else {
                    "Aucune donnée réseau sur cet appareil. Vérifiez la connexion dans Réglages."
                },
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                SectionHeader(
                    if (searching) {
                        "${rows.size} résultat(s)"
                    } else {
                        "Autour de ${origin?.name ?: "vous"}"
                    },
                )
            }

            items(rows, key = { it.stop.id }) { row ->
                StopRow(
                    stop = row.stop,
                    distanceMeters = row.distanceMeters,
                    routes = row.routes,
                    isFavorite = row.stop.id in favorites,
                    onClick = {
                        vm.selectedStopId.value = row.stop.id
                        vm.pushLocation(row.stop.lat, row.stop.lon)
                    },
                    onToggleFavorite = { vm.toggleFavorite(row.stop.id) },
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
}