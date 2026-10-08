package fr.tear36.blus.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

data class RealtimeSnapshot(
    val vehicles: List<Vehicle>,
    val alerts: List<TrafficAlert>,
    val feedTimestamp: Long,
    val fetchedAt: Long,
    val activeTripCount: Int,
    val error: String? = null,
)

sealed class FeedState {
    object Idle : FeedState()
    data class Downloading(val bytesRead: Long, val total: Long) : FeedState()
    data class Importing(val file: String, val fraction: Float) : FeedState()
    data class Ready(val stops: Int, val routes: Int) : FeedState()
    data class Failed(val message: String) : FeedState()
}

/**
 * Owns the local GTFS database and the GTFS-RT polling loop.
 *
 * The producer publishes `TripUpdate` only (no `VehiclePosition`), so map positions are
 * interpolated along the GTFS shape between the last and next known stop.
 */
class TransitRepository(context: Context) {

    private val appContext = context.applicationContext
    val dbHelper = GtfsDb(appContext)
    private val network = NetworkClient(appContext.cacheDir)
    private val shapeCache = HashMap<String, ShapeGeometry>()

    val database: SQLiteDatabase get() = dbHelper.readableDatabase

    // ---------- Static feed bootstrap ----------

    fun hasNetwork(): Boolean =
        (database.rawQuery("SELECT COUNT(*) FROM stops", null).use {
            it.moveToFirst(); it.getInt(0)
        }) > 0

    fun isFeedFresh(): Boolean {
        val imported = metaLong(GtfsDb.KEY_IMPORTED_AT) ?: return false
        val validTo = metaLong(GtfsDb.KEY_FEED_VALID_TO) ?: return true
        val now = System.currentTimeMillis()
        val refreshAt = if (validTo > 0) validTo - 3 * 86_400_000L else Long.MAX_VALUE
        return now < refreshAt && (now - imported) < 30 * 86_400_000L
    }

    private fun metaLong(key: String): Long? =
        database.meta(key)?.toLongOrNull()

    suspend fun bootstrap(
        onState: (FeedState) -> Unit,
        force: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (hasNetwork() && isFeedFresh() && !force) {
            onState(FeedState.Ready(countStops(), countRoutes()))
            return@withContext true
        }
        onState(FeedState.Downloading(0, -1))
        try {
            val zip = network.downloadGtfs { read, total ->
                onState(FeedState.Downloading(read, total))
            }

            val db = dbHelper.writableDatabase
            listOf("stops", "routes", "trips", "stop_times", "shapes", "calendar", "calendar_dates", "meta")
                .forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            dbHelper.onCreate(db)

            val summary = GtfsImporter.import(zip, db) { p ->
                onState(FeedState.Importing(p.file, p.fraction))
            }
            db.putMeta(GtfsDb.KEY_IMPORTED_AT, System.currentTimeMillis().toString())

            readFeedInfo(zip)?.let { (published, validTo) ->
                if (!published.isNullOrBlank()) db.putMeta(GtfsDb.KEY_FEED_PUBLISHED, published)
                if (!validTo.isNullOrBlank()) db.putMeta(GtfsDb.KEY_FEED_VALID_TO, validTo)
            }

            zip.delete()
            shapeCache.clear()
            Log.i(TAG, "GTFS import: $summary")
            onState(FeedState.Ready(summary.stops, summary.routes))
            true
        } catch (e: Exception) {
            Log.e(TAG, "GTFS bootstrap failed", e)
            onState(FeedState.Failed(e.message ?: "échec du téléchargement"))
            false
        }
    }

    private fun countStops() = database.rawQuery("SELECT COUNT(*) FROM stops", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    private fun countRoutes() = database.rawQuery("SELECT COUNT(*) FROM routes", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    /** Reads feed_publisher_name / feed_start_date from agency.txt for the "à jour" label. */
    private fun readFeedInfo(zip: File): Pair<String?, String?> {
        return try {
            java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.substringAfterLast('/') == "agency.txt") {
                        val text = zis.bufferedReader(Charsets.UTF_8).readText()
                        return@use parseAgency(text)
                    }
                    entry = zis.nextEntry
                }
                null to null
            } ?: (null to null)
        } catch (e: Exception) {
            null to null
        }
    }

    private fun parseAgency(text: String): Pair<String?, String?> {
        val first = text.lineSequence().drop(1).firstOrNull() ?: return (null to null)
        val f = splitCsvLine(first)
        val name = f.getOrNull(1)?.takeIf { it.isNotBlank() }
        val start = f.getOrNull(8)?.takeIf { it.isNotBlank() }
        val end = f.getOrNull(9)?.takeIf { it.isNotBlank() }
        val validTo = end?.let { parseGtfsDate(it) }
        return name to (validTo?.let { it.toString() })
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>(12)
        var i = 0
        while (i <= line.length) {
            if (i == line.length) break
            val sb = StringBuilder()
            if (line[i] == '"') {
                i++
                while (i < line.length) {
                    if (line[i] == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i += 2 }
                        else { i++; break }
                    } else { sb.append(line[i]); i++ }
                }
                while (i < line.length && line[i] != ',') i++
            } else {
                while (i < line.length && line[i] != ',') { sb.append(line[i]); i++ }
            }
            out.add(sb.toString())
            if (i < line.length && line[i] == ',') i++
        }
        return out
    }

    // ---------- Real time ----------

    fun realtimeFlow(periodMillis: Long = 20_000L): Flow<RealtimeSnapshot> = flow {
        var lastOk = 0L
        while (true) {
            val snapshot = poll()
            if (snapshot.error != null && lastOk > 0) {
                // Keep serving the previous snapshot for a while rather than blanking the map.
                emit(snapshot.copy(vehicles = cachedVehicles, error = snapshot.error))
            } else {
                if (snapshot.error == null) {
                    cachedVehicles = snapshot.vehicles
                    lastOk = System.currentTimeMillis()
                }
                emit(snapshot)
            }
            kotlinx.coroutines.delay(periodMillis)
        }
    }.flowOn(Dispatchers.IO)

    private var cachedVehicles: List<Vehicle> = emptyList()

    suspend fun poll(): RealtimeSnapshot {
        val now = System.currentTimeMillis()
        var feed: GtfsRt.Feed? = null
        var error: String? = null
        try {
            feed = GtfsRt.parse(network.fetchTripUpdates())
        } catch (e: Exception) {
            error = "Flux temps réel indisponible"
        }

        var alerts: List<TrafficAlert> = emptyList()
        try {
            val alertFeed = GtfsRt.parse(network.fetchAlerts())
            alerts = alertFeed.alerts.map { it.toTrafficAlert() }
        } catch (e: Exception) {
            if (error == null && feed != null) error = null
        }

        val vehicles = if (feed == null) emptyList() else buildVehicles(feed, now)
        return RealtimeSnapshot(
            vehicles = vehicles,
            alerts = alerts.filter { isAlertActive(it, now) },
            feedTimestamp = feed?.timestamp ?: 0L,
            fetchedAt = now,
            activeTripCount = feed?.trips?.size ?: 0,
            error = error,
        )
    }

    private fun isAlertActive(a: TrafficAlert, now: Long): Boolean {
        val from = a.validFrom
        val to = a.validUntil
        if (from != null && now < from - 86_400_000L) return false
        if (to != null && now > to) return false
        return true
    }

    private fun GtfsRt.RtAlert.toTrafficAlert(): TrafficAlert {
        val lineNames = routes.map { it.substringAfterLast(':') }.distinct()
        // A third of the published alerts carry no header nor description: they only say
        // which lines are affected. Build a readable title from those instead.
        val fallback = buildString {
            val topic = if (effect > 1 && effect != 10 && effect != 11) {
                GtfsRt.effectLabel(effect)
            } else {
                GtfsRt.causeLabel(cause)
            }
            append(topic)
            if (lineNames.isNotEmpty()) {
                append(" — ")
                append(lineNames.take(3).joinToString(", "))
                if (lineNames.size > 3) append(" et ${lineNames.size - 3} autres")
            }
        }
        return TrafficAlert(
            id = id,
            header = header.ifBlank { description.take(80) }.ifBlank { fallback },
            description = description,
            severity = GtfsRt.severityLabel(severity),
            cause = GtfsRt.causeLabel(cause),
            effect = GtfsRt.effectLabel(effect),
            lines = lineNames,
            url = url,
            validFrom = start,
            validUntil = end,
        )
    }

    // ---------- Vehicle interpolation ----------

    private class ShapeGeometry(
        val points: List<ShapePoint>,
        val stopIndex: Map<String, Int>,
    )

    private fun geometryFor(shapeId: String): ShapeGeometry? = shapeCache.getOrPut(shapeId) {
        val pts = database.shape(shapeId)
        if (pts.isEmpty()) return@getOrPut ShapeGeometry(emptyList(), emptyMap())
        val stopIdx = HashMap<String, Int>(128)
        val quays = database.rawQuery(
            """
            SELECT DISTINCT s.id, s.lat, s.lon FROM stop_times st
            JOIN stops s ON s.id = st.stop_id
            WHERE st.trip_id IN (SELECT id FROM trips WHERE shape_id = ? LIMIT 40)
            """.trimIndent(),
            arrayOf(shapeId),
        )
        quays.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val lat = c.getDouble(1)
                val lon = c.getDouble(2)
                var best = 0
                var bestD = Double.MAX_VALUE
                for (i in pts.indices) {
                    val d = (pts[i].lat - lat) * (pts[i].lat - lat) +
                        (pts[i].lon - lon) * (pts[i].lon - lon)
                    if (d < bestD) { bestD = d; best = i }
                }
                stopIdx[id] = best
            }
        }
        ShapeGeometry(pts, stopIdx)
    }

    private fun buildVehicles(feed: GtfsRt.Feed, now: Long): List<Vehicle> {
        val out = ArrayList<Vehicle>(feed.trips.size)

        // Exact positions if the producer ever enables VehiclePosition.
        val byTrip = HashMap<String, GtfsRt.RtVehiclePosition>(feed.vehicles.size)
        for (v in feed.vehicles) byTrip[v.tripId] = v

        for (t in feed.trips) {
            if (t.calls.isEmpty() && byTrip[t.tripId] == null) continue
            val next = t.calls.firstOrNull { it.arrivalTime > now } ?: t.calls.lastOrNull()
            val prev = t.calls.lastOrNull { it.arrivalTime <= now }

            val staticTrip = database.trip(t.tripId)
            val routeId = t.routeId.ifBlank { staticTrip?.routeId.orEmpty() }
            if (routeId.isEmpty()) continue

            val shapeId = staticTrip?.shapeId
            var lat: Double? = null
            var lon: Double? = null
            var bearing: Float? = null
            var progress = 0f

            val exact = byTrip[t.tripId]
            if (exact != null) {
                lat = exact.lat
                lon = exact.lon
                bearing = exact.bearing
                progress = 0.5f
            } else if (shapeId != null) {
                val geo = geometryFor(shapeId)
                val fromIdx = prev?.stopId?.let { geo?.stopIndex?.get(it) }
                val toIdx = next?.stopId?.let { geo?.stopIndex?.get(it) }
                if (geo != null && geo.points.isNotEmpty()) {
                    if (fromIdx != null && toIdx != null && fromIdx != toIdx) {
                        val t0 = prev?.departureTime ?: 0L
                        val t1 = next?.arrivalTime ?: 0L
                        if (t1 > t0) {
                            progress = ((now - t0).toFloat() / (t1 - t0)).coerceIn(0f, 1f)
                            val p = interpolate(geo.points, fromIdx, toIdx, progress)
                            lat = p.first
                            lon = p.second
                            bearing = bearingBetween(geo.points, fromIdx, toIdx, progress)
                        }
                    }
                    if (lat == null && toIdx != null) {
                        val p = geo.points[toIdx]
                        lat = p.lat; lon = p.lon; progress = 0f
                    } else if (lat == null && fromIdx != null) {
                        val p = geo.points[fromIdx]
                        lat = p.lat; lon = p.lon; progress = 1f
                    }
                }
            }

            // Delay against the published schedule, when we can compute it.
            var delay = t.delay
            if (delay == 0 && next != null && staticTrip != null) {
                val scheduled = scheduledDepartureFor(t.tripId, next.stopId ?: "", next.stopSequence)
                if (scheduled > 0) {
                    delay = ((next.arrivalTime - scheduled) / 60L).toInt()
                }
            }

            out.add(
                Vehicle(
                    tripId = t.tripId,
                    routeId = routeId,
                    headsign = t.headsign.ifBlank { staticTrip?.headsign.orEmpty() },
                    directionId = t.directionId,
                    lat = lat,
                    lon = lon,
                    bearing = bearing,
                    progress = progress,
                    nextStopId = next?.stopId,
                    nextStopName = next?.stopId?.let { stopName(it) },
                    nextArrivalEpoch = next?.arrivalTime?.takeIf { it > 0 },
                    delaySec = delay * 60,
                    realtime = exact != null,
                    updatedAt = now,
                )
            )
        }
        return out
    }

    private val stopNameCache = HashMap<String, String>()

    private fun stopName(id: String): String? = stopNameCache.getOrPut(id) {
        database.rawQuery("SELECT name FROM stops WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getString(0) else ""
        }
    }

    private fun scheduledDepartureFor(tripId: String, stopId: String, seq: Int): Long {
        if (stopId.isEmpty()) return 0
        return database.rawQuery(
            "SELECT departure FROM stop_times WHERE trip_id = ? AND stop_id = ? AND seq = ?",
            arrayOf(tripId, stopId, seq.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0).toLong() else 0L }
    }

    private fun interpolate(
        pts: List<ShapePoint>,
        from: Int,
        to: Int,
        t: Float,
    ): Pair<Double, Double> {
        val step = if (to > from) 1 else -1
        val a = pts[from]
        val b = pts[to]
        return Pair(
            a.lat + (b.lat - a.lat) * t,
            a.lon + (b.lon - a.lon) * t,
        ).also { if (step == 0) Unit }
    }

    private fun bearingBetween(pts: List<ShapePoint>, from: Int, to: Int, t: Float): Float? {
        val span = abs(to - from)
        if (span == 0) return null
        val i1 = if (to > from) (from + (to - from) * t).toInt().coerceIn(0, pts.size - 1) else from
        val i2 = (if (to > from) i1 + 1 else i1 - 1).coerceIn(0, pts.size - 1)
        if (i1 == i2) return null
        val p1 = pts[i1]
        val p2 = pts[i2]
        val dLon = (p2.lon - p1.lon) * cosOf(p1.lat)
        val dLat = p2.lat - p1.lat
        if (abs(dLon) < 1e-9 && abs(dLat) < 1e-9) return null
        val deg = Math.toDegrees(Math.atan2(dLon, dLat)).toFloat()
        return (deg + 360f) % 360f
    }

    private fun cosOf(lat: Double) = Math.cos(Math.toRadians(lat))

    // ---------- Departures ----------

    data class Departure(
        val routeId: String,
        val headsign: String,
        val directionId: Int,
        val epoch: Long,
        val realtime: Boolean,
        val delaySec: Int,
        val tripId: String,
        val cancelled: Boolean = false,
    )

    /**
     * Next departures at a stop (a station or one of its quays): live ones first, then scheduled.
     */
    fun departures(stopId: String, snapshot: RealtimeSnapshot?, max: Int = 12): List<Departure> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Departure>(max)
        val quays = database.quayIdsFor(stopId)
        val quaySet = HashSet(quays)

        snapshot?.vehicles?.forEach { v ->
            if (v.nextStopId in quaySet && v.nextArrivalEpoch != null && v.nextArrivalEpoch > now - 60_000) {
                out.add(
                    Departure(
                        routeId = v.routeId,
                        headsign = v.headsign,
                        directionId = v.directionId,
                        epoch = v.nextArrivalEpoch,
                        realtime = true,
                        delaySec = v.delaySec,
                        tripId = v.tripId,
                    )
                )
            }
        }

        val fromSec = TimeUtils.secondsSinceLocalMidnight(now) - 300
        val serviceIds = database.serviceIdsOn(TimeUtils.gtfsDate(now))
        val known = HashSet<String>()
        out.forEach { known.add(it.tripId) }

        database.scheduledCalls(quays, serviceIds, fromSec, max * 4).forEach { call ->
            if (call.tripId in known) return@forEach
            val epoch = TimeUtils.localMidnightPlus(call.arrivalSec, now)
            if (epoch < now - 60_000) return@forEach
            known.add(call.tripId)
            out.add(
                Departure(
                    routeId = call.routeId,
                    headsign = call.headsign,
                    directionId = call.directionId,
                    epoch = epoch,
                    realtime = false,
                    delaySec = 0,
                    tripId = call.tripId,
                )
            )
        }

        return out.sortedBy { it.epoch }.take(max)
    }

    /** Real-time departures only (fast path, no schedule lookup). */
    fun liveDepartures(stopId: String, snapshot: RealtimeSnapshot?, max: Int = 12): List<Departure> {
        val now = System.currentTimeMillis()
        val quaySet = HashSet(database.quayIdsFor(stopId))
        return snapshot?.vehicles.orEmpty()
            .filter { it.nextStopId in quaySet && (it.nextArrivalEpoch ?: 0L) > now - 60_000 }
            .sortedBy { it.nextArrivalEpoch }
            .take(max)
            .map {
                Departure(
                    routeId = it.routeId,
                    headsign = it.headsign,
                    directionId = it.directionId,
                    epoch = it.nextArrivalEpoch ?: 0L,
                    realtime = true,
                    delaySec = it.delaySec,
                    tripId = it.tripId,
                )
            }
    }

    fun routes(): Map<String, Route> = database.routes()
    fun allStops(): List<Stop> = database.allStops()

    fun feedPublished(): String? = database.meta(GtfsDb.KEY_FEED_PUBLISHED)

    fun feedValidUntil(): String? = database.meta(GtfsDb.KEY_FEED_VALID_TO)?.let { raw ->
        try {
            GTFS_DATES.get()?.format(Date(raw.toLongOrNull() ?: 0L))
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "Blus/Transit"
        val GTFS_DATES = object : ThreadLocal<SimpleDateFormat?>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).apply {
                    timeZone = TimeZone.getTimeZone("Europe/Paris")
                }
        }
    }
}

/** Time helpers anchored on Europe/Paris (the GTFS feed timezone). */
object TimeUtils {
    private val zone: TimeZone = TimeZone.getTimeZone("Europe/Paris")

    private fun cal(millis: Long): Calendar =
        Calendar.getInstance(zone, Locale.FRANCE).apply { timeInMillis = millis }

    fun secondsSinceLocalMidnight(millis: Long): Int {
        val c = cal(millis)
        return c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)
    }

    fun localMidnightPlus(secOfDay: Int, reference: Long): Long {
        val c = cal(reference)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        var target = c.timeInMillis + secOfDay * 1000L
        // GTFS times may exceed 24h (e.g. 25:10:00 on a night service).
        val refSec = secondsSinceLocalMidnight(reference)
        if (secOfDay < refSec - 43_200) target += 86_400_000L
        return target
    }

    fun gtfsDate(millis: Long): String {
        val c = cal(millis)
        return String.format(
            Locale.US,
            "%04d%02d%02d",
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }

    private val HHMM = object : ThreadLocal<SimpleDateFormat?>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm", Locale.FRANCE).apply { timeZone = zone }
    }

    private val HHMMSS = object : ThreadLocal<SimpleDateFormat?>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss", Locale.FRANCE).apply { timeZone = zone }
    }

    fun formatHm(millis: Long): String = HHMM.get()!!.format(Date(millis))
    fun formatHms(millis: Long): String = HHMMSS.get()!!.format(Date(millis))

    private val DAY_MONTH = object : ThreadLocal<SimpleDateFormat?>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("EEE d MMM", Locale.FRANCE).apply { timeZone = zone }
    }

    fun formatDay(millis: Long): String = DAY_MONTH.get()!!.format(Date(millis))
}

private fun parseGtfsDate(s: String): Long? = try {
    val c = java.util.Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"), Locale.FRANCE)
    c.clear()
    c.set(s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt())
    c.timeInMillis
} catch (e: Exception) {
    null
}