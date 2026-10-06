package fr.tear36.blus.ui

import androidx.compose.ui.graphics.Color

/** Parses the `#rrggbb` colours published in GTFS `routes.txt`. */
fun parseGtfsColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    val h = hex.trim().removePrefix("#")
    if (h.length != 6) return fallback
    val v = h.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000 or v)
}

fun routeModeIcon(mode: Int): String = when (mode) {
    0 -> "T"        // tramway
    1 -> "M"        // métro
    2 -> "R"        // train
    3 -> "B"        // bus
    4 -> "F"        // ferry
    else -> "B"
}

fun routeModeLabel(mode: Int): String = when (mode) {
    0 -> "Tramway"
    1 -> "Métro"
    2 -> "Train"
    3 -> "Bus"
    4 -> "Navette"
    else -> "Transport"
}

fun formatDistance(meters: Double): String = when {
    meters < 950 -> "${(meters / 10).toInt() * 10} m"
    else -> String.format(java.util.Locale.FRANCE, "%.1f km", meters / 1000.0)
}

fun formatDelay(seconds: Int): String = when {
    seconds <= 60 -> "à l'heure"
    seconds < 3600 -> "+${(seconds / 60.0).toInt()} min"
    else -> "+${(seconds / 3600.0).toInt()} h ${(seconds % 3600) / 60}"
}

fun formatEta(epoch: Long, now: Long): String {
    val delta = (epoch - now) / 1000L
    return when {
    delta < 30 -> "imminent"
        delta < 60 -> "1 min"
        delta < 3600 -> "${delta / 60} min"
        else -> "${delta / 3600} h ${(delta % 3600) / 60}"
    }
}