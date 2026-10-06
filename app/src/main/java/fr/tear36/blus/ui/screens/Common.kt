package fr.tear36.blus.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.tear36.blus.data.Route
import fr.tear36.blus.data.Stop
import fr.tear36.blus.data.TimeUtils
import fr.tear36.blus.data.TransitRepository.Departure
import fr.tear36.blus.ui.formatDelay
import fr.tear36.blus.ui.formatDistance
import fr.tear36.blus.ui.formatEta
import fr.tear36.blus.ui.parseGtfsColor
import fr.tear36.blus.data.haversineMeters

/** Coloured line badge, e.g. a green "1" or a red "C3". */
@Composable
fun RouteBadge(route: Route?, size: Int = 30, modifier: Modifier = Modifier) {
    val bg = parseGtfsColor(route?.color, MaterialTheme.colorScheme.primary)
    val fg = parseGtfsColor(route?.textColor, Color.White)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = route?.shortName.orEmpty().take(4),
            color = fg,
            fontSize = when {
                route == null -> 12.sp
                route.shortName.length > 3 -> 11.sp
                route.shortName.length > 2 -> 13.sp
                else -> 15.sp
            },
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun StopRow(
    stop: Stop,
    distanceMeters: Double?,
    routes: List<Route>,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stop.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildString {
                    if (distanceMeters != null) append(formatDistance(distanceMeters))
                    if (distanceMeters != null && routes.isNotEmpty()) append(" · ")
                    if (routes.isNotEmpty()) {
                        append(routes.take(6).joinToString(" ") { it.shortName })
                        if (routes.size > 6) append(" +${routes.size - 6}")
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = if (isFavorite) {
                        Color(0xFFFFC107)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
fun DepartureRow(departure: Departure, route: Route?, now: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteBadge(route, size = 28)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = departure.headsign.ifBlank { route?.longName.orEmpty().take(40) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${TimeUtils.formatHm(departure.epoch)} · ${
                    if (departure.realtime) "temps réel" else "théorique"
                }",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatEta(departure.epoch, now),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (departure.realtime && departure.delaySec != 0) {
                Text(
                    text = formatDelay(departure.delaySec),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (departure.delaySec > 120) {
                        Color(0xFFFF6B6B)
                    } else {
                        Color(0xFFFFC107)
                    },
                )
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
fun LiveDot(active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                if (active) Color(0xFF44D07B) else Color(0xFF7A8791),
            ),
    )
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

fun distanceBetween(a: Stop, b: Stop): Double = haversineMeters(a.lat, a.lon, b.lat, b.lon)

@Composable
fun StopsList(
    stops: List<Stop>,
    origin: Stop?,
    favorites: Set<String>,
    routesAt: (Stop) -> List<Route>,
    onClick: (Stop) -> Unit,
    onToggleFavorite: (Stop) -> Unit,
    departuresOf: @Composable (Stop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(stops, key = { it.id }) { stop ->
            StopRow(
                stop = stop,
                distanceMeters = origin?.let { distanceBetween(stop, it) },
                routes = routesAt(stop),
                isFavorite = stop.id in favorites,
                onClick = { onClick(stop) },
                onToggleFavorite = { onToggleFavorite(stop) },
                content = { departuresOf(stop) },
            )
        }
    }
}