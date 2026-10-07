package fr.tear36.blus.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.tear36.blus.BuildConfig
import fr.tear36.blus.data.BuildInfo
import fr.tear36.blus.data.FeedState
import fr.tear36.blus.data.Stop
import fr.tear36.blus.ui.BlusViewModel
import fr.tear36.blus.ui.UpdateState
import fr.tear36.blus.ui.formatDistance
import fr.tear36.blus.data.haversineMeters
import fr.tear36.blus.update.ApkInstaller

@Composable
fun SettingsScreen(vm: BlusViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val feed by vm.feedState.collectAsState()
    val update by vm.updateState.collectAsState()
    val favorites by vm.favorites.collectAsState()

    var favStops by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var published by remember { mutableStateOf<String?>(null) }
    var validTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(favorites) { favStops = vm.favoriteStops() }
    LaunchedEffect(feed) {
        if (feed is FeedState.Ready) {
            published = vm.repoRef.feedPublished()
            validTo = vm.repoRef.feedValidUntil()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
    ) {
        item {
            Text(
                "Mise à jour",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            )
            UpdateCard(context, update, vm)
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Données réseau", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.forceFeedRefresh() }) { Text("Actualiser") }
            }
            FeedCard(feed, published, validTo, onRetry = { vm.forceFeedRefresh() })
        }

        if (favStops.isNotEmpty()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Favoris",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${favStops.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(favStops, key = { it.id }) { stop ->
                val origin = vm.location.value
                FavoriteRow(
                    stop = stop,
                    distance = origin?.let { haversineMeters(stop.lat, stop.lon, it.lat, it.lon) },
                    onClick = {
                        vm.selectedStopId.value = stop.id
                        vm.pushLocation(stop.lat, stop.lon)
                    },
                    onRemove = { vm.toggleFavorite(stop.id) },
                )
            }
        }

        item { AboutCard(context) }
    }
}

@Composable
private fun UpdateCard(context: Context, state: UpdateState, vm: BlusViewModel) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Version installée", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (state) {
                    is UpdateState.Checking, is UpdateState.Downloading ->
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else -> Unit
                }
            }

            Spacer(Modifier.size(12.dp))

            when (state) {
                UpdateState.Idle -> Button(
                    onClick = { vm.checkUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Rechercher une mise à jour") }

                UpdateState.Checking -> Text(
                    "Vérification des versions publiées…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                UpdateState.UpToDate -> Text(
                    "Blus est à jour.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                is UpdateState.Available -> Column {
                    Text(
                        "Version ${state.update.versionName} disponible",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.update.notes.isNotBlank()) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = state.update.notes.take(400),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.downloadUpdate(state.update) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Télécharger") }
                        OutlinedButton(onClick = { vm.resetUpdateState() }) { Text("Plus tard") }
                    }
                }

                is UpdateState.Downloading -> Column {
                    Text(
                        "Téléchargement de l'APK…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (state.total > 0) (state.read.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is UpdateState.Ready -> Column {
                    Text(
                        "Version ${state.versionName} prête à installer",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Android va vous demander de confirmer l'installation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    Button(
                        onClick = {
                            val file = java.io.File(state.filePath)
                            // Android 8+ requires a per-app "unknown sources" grant first.
                            val settingsIntent = ApkInstaller.requestUnknownSourcesPermission(context)
                            if (settingsIntent != null) {
                                runCatching { context.startActivity(settingsIntent) }
                                    .onFailure {
                                        Toast.makeText(context, "Activez l'installation depuis cette source dans les réglages Android.", Toast.LENGTH_LONG).show()
                                    }
                            } else {
                                runCatching {
                                    context.sendBroadcast(ApkInstaller.intent(context, file))
                                }.onFailure {
                                    Toast.makeText(context, "Installation impossible", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (ApkInstaller.requestUnknownSourcesPermission(context) != null) {
                                "Autoriser l'installation"
                            } else {
                                "Installer la mise à jour"
                            }
                        )
                    }
                }

                is UpdateState.Error -> Column {
                    Text(
                        "Mise à jour indisponible : ${state.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { vm.checkUpdate() }) { Text("Réessayer") }
                }
            }
        }
    }
}

@Composable
private fun FeedCard(
    state: FeedState,
    published: String?,
    validTo: String?,
    onRetry: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is FeedState.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveDot(true)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Réseau disponible",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "${state.stops} arrêts · ${state.routes} lignes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (published != null) {
                        Text(
                            "Producteur : $published",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (validTo != null) {
                        Text(
                            "Horaires valides jusqu'au $validTo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is FeedState.Downloading -> Column {
                    Text("Téléchargement du GTFS…", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (state.total > 0) (state.bytesRead.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.total > 0) {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "${state.bytesRead / 1_048_576} / ${state.total / 1_048_576} Mo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is FeedState.Importing -> Column {
                    Text(
                        "Import de « ${state.file} »…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(8.dp))
                    LinearProgressIndicator(
                        progress = { state.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is FeedState.Failed -> Column {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = onRetry) { Text("Réessayer") }
                }

                FeedState.Idle -> Text("Initialisation…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FavoriteRow(stop: Stop, distance: Double?, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Star,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color(0xFFFFC107),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stop.name, style = MaterialTheme.typography.bodyLarge)
            if (distance != null) {
                Text(
                    formatDistance(distance),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Retirer",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutCard(context: Context) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("À propos", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Text(
                "Blus affiche les bus, tramways et perturbations du réseau Naolib " +
                    "(Nantes Métropole) à partir des données ouvertes officielles.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(10.dp))
            AttributionRow(
                "Données temps réel : GTFS-RT via transport.data.gouv.fr",
            )
            AttributionRow("Données statiques : GTFS Nantes Métropole (Licence Ouverte 2.0)")
            AttributionRow("Carte : © contributeurs OpenStreetMap (ODbL)")
            AttributionRow("Positions affichées estimées entre deux arrêts connus")

            Spacer(Modifier.size(10.dp))
            TextButton(onClick = {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/${BuildInfo.REPO}"),
                )
                runCatching { context.startActivity(intent) }
            }) {
                Text("Code source : github.com/${BuildInfo.REPO}")
            }
        }
    }
}

@Composable
private fun AttributionRow(text: String) {
    Text(
        "· $text",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}