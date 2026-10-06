package fr.tear36.blus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.tear36.blus.data.TimeUtils
import fr.tear36.blus.data.TrafficAlert
import fr.tear36.blus.ui.BlusViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun TrafficScreen(vm: BlusViewModel, modifier: Modifier = Modifier) {
    val snapshot by vm.realtime.collectAsState()
    val now = System.currentTimeMillis()

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("État du trafic", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(snapshot.error == null && snapshot.vehicles.isNotEmpty())
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = statusLine(snapshot.error, snapshot.activeTripCount, snapshot.fetchedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { vm.refreshRealtimeNow() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Rafraîchir",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (snapshot.alerts.isEmpty()) {
            EmptyHint("Aucune perturbation en cours sur le réseau Naolib.")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item { SectionHeader("${snapshot.alerts.size} perturbation(s)") }
            items(snapshot.alerts, key = { it.id }) { alert ->
                AlertCard(alert)
            }
            item { Spacer(Modifier.size(96.dp)) }
        }
    }
}

private fun statusLine(error: String?, trips: Int, fetchedAt: Long): String {
    if (error != null) return "Flux temps réel indisponible"
    val time = TimeUtils.formatHm(fetchedAt)
    return "$trips véhicules en service · actualisé à $time"
}

@Composable
private fun AlertCard(alert: TrafficAlert) {
    val accent = when (alert.severity) {
        "important" -> Color(0xFFE5484D)
        "attention" -> Color(0xFFF5A524)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = alert.header,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                    )
                    Text(
                        text = "${alert.cause} · ${alert.effect}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (alert.description.isNotBlank() && alert.description != alert.header) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = alert.description.take(400),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (alert.lines.isNotEmpty()) {
                Spacer(Modifier.size(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    alert.lines.take(10).forEach { line ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            val validity = formatValidity(alert)
            if (validity != null) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = validity,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val DAY_HOUR = SimpleDateFormat("EEE d MMM à HH:mm", Locale.FRANCE).apply {
    timeZone = TimeZone.getTimeZone("Europe/Paris")
}

private fun formatValidity(alert: TrafficAlert): String? {
    val from: Long? = alert.validFrom
    val until: Long? = alert.validUntil
    if (from == null) {
        return if (until == null) null else "Jusqu'au ${DAY_HOUR.format(Date(until))}"
    }
    if (until == null) return "À partir du ${DAY_HOUR.format(Date(from))}"
    return "Du ${DAY_HOUR.format(Date(from))} au ${DAY_HOUR.format(Date(until))}"
}