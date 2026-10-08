package fr.tear36.blus.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local SQLite cache of the Naolib GTFS feed.
 *
 * The upstream GTFS bundle is ~27 MB (mostly stop_times) and is refreshed on demand;
 * we persist it so the map and the search work fully offline.
 */
class GtfsDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // `PRAGMA journal_mode` answers with a row, and a statement that returns rows
        // cannot go through execSQL — Android answers "Queries can be performed using
        // SQLiteDatabase query or rawQuery methods only", which used to kill the app
        // as soon as it opened the database. Asking for it as a query keeps both PRAGMAs.
        db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE stops(
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              lat REAL NOT NULL,
              lon REAL NOT NULL,
              location_type INTEGER NOT NULL DEFAULT 0,
              parent_station TEXT,
              wheelchair INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_stops_geo ON stops(lat, lon)")
        db.execSQL("CREATE INDEX idx_stops_parent ON stops(parent_station)")
        db.execSQL("CREATE INDEX idx_stops_name ON stops(name)")

        db.execSQL(
            """
            CREATE TABLE routes(
              id TEXT PRIMARY KEY,
              short_name TEXT NOT NULL,
              long_name TEXT,
              mode INTEGER NOT NULL DEFAULT 3,
              color TEXT,
              text_color TEXT,
              sort_order INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE trips(
              id TEXT PRIMARY KEY,
              route_id TEXT NOT NULL,
              service_id TEXT NOT NULL,
              headsign TEXT,
              direction_id INTEGER NOT NULL DEFAULT 0,
              shape_id TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_trips_service ON trips(service_id)")
        db.execSQL("CREATE INDEX idx_trips_route ON trips(route_id)")

        db.execSQL(
            """
            CREATE TABLE stop_times(
              trip_id TEXT NOT NULL,
              seq INTEGER NOT NULL,
              stop_id TEXT NOT NULL,
              arrival INTEGER NOT NULL,
              departure INTEGER NOT NULL,
              PRIMARY KEY(trip_id, seq)
            ) WITHOUT ROWID
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_stoptimes_stop ON stop_times(stop_id)")

        db.execSQL(
            """
            CREATE TABLE shapes(
              shape_id TEXT NOT NULL,
              seq INTEGER NOT NULL,
              lat REAL NOT NULL,
              lon REAL NOT NULL,
              PRIMARY KEY(shape_id, seq)
            ) WITHOUT ROWID
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE calendar(
              service_id TEXT PRIMARY KEY,
              days_mask INTEGER NOT NULL,
              start_date TEXT,
              end_date TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE calendar_dates(
              service_id TEXT NOT NULL,
              date TEXT NOT NULL,
              exception_type INTEGER NOT NULL,
              PRIMARY KEY(service_id, date)
            ) WITHOUT ROWID
            """.trimIndent()
        )

        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf(
            "stops", "routes", "trips", "stop_times", "shapes",
            "calendar", "calendar_dates", "meta",
        ).forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "blus-gtfs.db"
        const val DB_VERSION = 1
        const val KEY_FEED_PUBLISHED = "feed_published"
        const val KEY_FEED_VALID_TO = "feed_valid_to"
        const val KEY_IMPORTED_AT = "imported_at"
    }
}

// ---------- Readers ----------

fun SQLiteDatabase.routes(): Map<String, Route> {
    val out = HashMap<String, Route>(128)
    rawQuery("SELECT id, short_name, long_name, mode, color, text_color, sort_order FROM routes", null)
        .use { c ->
            while (c.moveToNext()) {
                val r = Route(
                    id = c.getString(0),
                    shortName = c.getString(1).orEmpty(),
                    longName = c.getString(2),
                    mode = c.getInt(3),
                    color = c.getString(4).orEmpty(),
                    textColor = c.getString(5).orEmpty(),
                    sortOrder = c.getInt(6),
                )
                out[r.id] = r
            }
        }
    return out
}

fun SQLiteDatabase.allStops(): List<Stop> {
    val out = ArrayList<Stop>(4096)
    rawQuery(
        "SELECT id, name, lat, lon, location_type, parent_station, wheelchair FROM stops",
        null,
    ).use { c ->
        while (c.moveToNext()) out.add(c.toStop())
    }
    return out
}

fun Cursor.toStop() = Stop(
    id = getString(0),
    name = getString(1).orEmpty(),
    lat = getDouble(2),
    lon = getDouble(3),
    locationType = getInt(4),
    parentStation = if (isNull(5)) null else getString(5),
    wheelchair = getInt(6),
)

/** Stops within [radiusMeters] of the point, nearest first. */
fun SQLiteDatabase.stopsNear(
    lat: Double,
    lon: Double,
    radiusMeters: Int,
    limit: Int,
): List<Stop> {
    val dLat = radiusMeters / 111_320.0
    val dLon = radiusMeters / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
    val out = ArrayList<Stop>(64)
    rawQuery(
        """
        SELECT id, name, lat, lon, location_type, parent_station, wheelchair FROM stops
        WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
        """.trimIndent(),
        arrayOf(
            (lat - dLat).toString(),
            (lat + dLat).toString(),
            (lon - dLon).toString(),
            (lon + dLon).toString(),
        ),
    ).use { c ->
        val r = LatLonRadius(lat, lon, radiusMeters.toDouble())
        val hits = ArrayList<Pair<Double, Stop>>()
        while (c.moveToNext()) {
            val s = c.toStop()
            val d = r.distanceTo(s.lat, s.lon)
            if (d <= radiusMeters) hits.add(d to s)
        }
        hits.sortBy { it.first }
        for (i in 0 until minOf(limit, hits.size)) out.add(hits[i].second)
    }
    return out
}

/** Distinct logical stations (StopPlace) near a point. */
fun SQLiteDatabase.stationsNear(
    lat: Double,
    lon: Double,
    radiusMeters: Int,
    limit: Int,
): List<Stop> {
    val dLat = radiusMeters / 111_320.0
    val dLon = radiusMeters / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
    val out = ArrayList<Stop>(64)
    rawQuery(
        """
        SELECT id, name, lat, lon, location_type, parent_station, wheelchair FROM stops
        WHERE location_type = 1 AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
        """.trimIndent(),
        arrayOf(
            (lat - dLat).toString(),
            (lat + dLat).toString(),
            (lon - dLon).toString(),
            (lon + dLon).toString(),
        ),
    ).use { c ->
        val r = LatLonRadius(lat, lon, radiusMeters.toDouble())
        val hits = ArrayList<Pair<Double, Stop>>()
        while (c.moveToNext()) {
            val s = c.toStop()
            val d = r.distanceTo(s.lat, s.lon)
            if (d <= radiusMeters) hits.add(d to s)
        }
        hits.sortBy { it.first }
        for (i in 0 until minOf(limit, hits.size)) out.add(hits[i].second)
    }
    return out
}

fun SQLiteDatabase.searchStops(query: String, limit: Int): List<Stop> {
    val q = "%${query.trim().lowercase()}%"
    val out = ArrayList<Stop>(limit)
    rawQuery(
        """
        SELECT id, name, lat, lon, location_type, parent_station, wheelchair FROM stops
        WHERE location_type IN (0, 1) AND lower(name) LIKE ?
        ORDER BY location_type, name LIMIT ?
        """.trimIndent(),
        arrayOf(q, limit.toString()),
    ).use { c ->
        while (c.moveToNext()) out.add(c.toStop())
    }
    return out
}

/** All quays (stop_id in stop_times) belonging to the given stop ids (stations + quays). */
fun SQLiteDatabase.quaysFor(stopIds: Collection<String>): List<Stop> {
    if (stopIds.isEmpty()) return emptyList()
    val out = ArrayList<Stop>(stopIds.size * 3)
    stopIds.forEach { id ->
        rawQuery(
            "SELECT id, name, lat, lon, location_type, parent_station, wheelchair FROM stops WHERE parent_station = ? OR id = ?",
            arrayOf(id, id),
        ).use { c ->
            while (c.moveToNext()) out.add(c.toStop())
        }
    }
    return out
}

data class ScheduledCall(
    val tripId: String,
    val routeId: String,
    val headsign: String,
    val directionId: Int,
    val seq: Int,
    val arrivalSec: Int,
    val departureSec: Int,
)

/** Trips serving one of the given quays on one of the given service days, soonest first. */
fun SQLiteDatabase.scheduledCalls(
    stopIds: Collection<String>,
    serviceIds: Collection<String>,
    fromSec: Int,
    maxResults: Int,
): List<ScheduledCall> {
    if (serviceIds.isEmpty() || stopIds.isEmpty()) return emptyList()
    val stopPh = stopIds.joinToString(",") { "?" }
    val servicePh = serviceIds.joinToString(",") { "?" }
    val sql = """
        SELECT st.trip_id, t.route_id, t.headsign, t.direction_id, st.seq, st.arrival, st.departure
        FROM stop_times st
        JOIN trips t ON t.id = st.trip_id
        WHERE st.stop_id IN ($stopPh) AND t.service_id IN ($servicePh) AND st.arrival >= ?
        ORDER BY st.arrival LIMIT ?
    """.trimIndent()
    val args = stopIds.toTypedArray() + serviceIds.toTypedArray() +
        arrayOf(fromSec.toString(), maxResults.toString())
    val out = ArrayList<ScheduledCall>(maxResults)
    rawQuery(sql, args).use { c ->
        while (c.moveToNext()) {
            out.add(
                ScheduledCall(
                    tripId = c.getString(0),
                    routeId = c.getString(1),
                    headsign = c.getString(2).orEmpty(),
                    directionId = c.getInt(3),
                    seq = c.getInt(4),
                    arrivalSec = c.getInt(5),
                    departureSec = c.getInt(6),
                )
            )
        }
    }
    return out
}

fun SQLiteDatabase.tripsOf(routeId: String): List<Trip> {
    val out = ArrayList<Trip>()
    rawQuery(
        "SELECT id, route_id, service_id, headsign, direction_id, shape_id FROM trips WHERE route_id = ?",
        arrayOf(routeId),
    ).use { c ->
        while (c.moveToNext()) out.add(c.toTrip())
    }
    return out
}

fun SQLiteDatabase.trip(id: String): Trip? {
    rawQuery(
        "SELECT id, route_id, service_id, headsign, direction_id, shape_id FROM trips WHERE id = ?",
        arrayOf(id),
    ).use { c -> return if (c.moveToFirst()) c.toTrip() else null }
}

fun Cursor.toTrip() = Trip(
    id = getString(0),
    routeId = getString(1),
    serviceId = getString(2),
    headsign = getString(3).orEmpty(),
    directionId = getInt(4),
    shapeId = if (isNull(5)) null else getString(5),
)

/** Ordered stop ids of a trip. */
fun SQLiteDatabase.tripStopIds(tripId: String): List<String> {
    val out = ArrayList<String>(64)
    rawQuery(
        "SELECT stop_id FROM stop_times WHERE trip_id = ? ORDER BY seq",
        arrayOf(tripId),
    ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
    return out
}

/**
 * `stop_times` is keyed on quays, while the map and the nearby list work with stations
 * (StopPlace). Resolving a station to its quays is what makes departures show up at all.
 */
fun SQLiteDatabase.quayIdsFor(stopId: String): List<String> {
    val quays = ArrayList<String>(8)
    rawQuery(
        "SELECT id FROM stops WHERE parent_station = ?",
        arrayOf(stopId),
    ).use { c -> while (c.moveToNext()) quays.add(c.getString(0)) }
    return if (quays.isEmpty()) listOf(stopId) else quays
}

fun SQLiteDatabase.shape(shapeId: String): List<ShapePoint> {
    val out = ArrayList<ShapePoint>(128)
    rawQuery(
        "SELECT lat, lon FROM shapes WHERE shape_id = ? ORDER BY seq",
        arrayOf(shapeId),
    ).use { c -> while (c.moveToNext()) out.add(ShapePoint(c.getDouble(0), c.getDouble(1))) }
    return out
}

/** GTFS route ids serving a stop, used to colour stops on the map. */
fun SQLiteDatabase.routesAtStop(stopId: String): List<String> =
    routesAtStops(quayIdsFor(stopId))

fun SQLiteDatabase.routesAtStops(stopIds: Collection<String>): List<String> {
    if (stopIds.isEmpty()) return emptyList()
    val out = LinkedHashSet<String>(16)
    stopIds.chunked(400) { chunk ->
        val placeholders = chunk.joinToString(",") { "?" }
        rawQuery(
            "SELECT DISTINCT t.route_id FROM stop_times st JOIN trips t ON t.id = st.trip_id WHERE st.stop_id IN ($placeholders) LIMIT 80",
            chunk.toTypedArray(),
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
    }
    return out.toList()
}

/**
 * One representative shape per route, used to draw the lines on the map.
 * The Naolib bundle only carries ~8k shape points, so the whole network is cheap enough
 * to keep in memory; a single direction is enough since both share the same geometry.
 */
fun SQLiteDatabase.routeShapes(): Map<String, List<ShapePoint>> {
    val shapeByRoute = LinkedHashMap<String, String>(128)
    rawQuery(
        """
        SELECT route_id, shape_id FROM trips
        WHERE shape_id IS NOT NULL AND shape_id <> ''
        GROUP BY route_id, direction_id
        """.trimIndent(),
        null,
    ).use { c ->
        while (c.moveToNext()) {
            val routeId = c.getString(0)
            if (!shapeByRoute.containsKey(routeId)) shapeByRoute[routeId] = c.getString(1)
        }
    }
    val out = HashMap<String, List<ShapePoint>>(shapeByRoute.size)
    for ((routeId, shapeId) in shapeByRoute) {
        val points = shape(shapeId)
        if (points.size >= 2) out[routeId] = points
    }
    return out
}

/** One trip per direction of a route — the canonical itinerary used to list its stops. */
data class RouteVariant(val directionId: Int, val tripId: String, val headsign: String)

fun SQLiteDatabase.routeVariants(routeId: String): List<RouteVariant> {
    val out = ArrayList<RouteVariant>(2)
    rawQuery(
        "SELECT direction_id, id, headsign FROM trips WHERE route_id = ? GROUP BY direction_id",
        arrayOf(routeId),
    ).use { c ->
        while (c.moveToNext()) {
            out.add(
                RouteVariant(
                    directionId = c.getInt(0),
                    tripId = c.getString(1),
                    headsign = c.getString(2).orEmpty(),
                ),
            )
        }
    }
    return out.sortedBy { it.directionId }
}

fun SQLiteDatabase.stopsById(ids: Collection<String>): Map<String, Stop> {
    if (ids.isEmpty()) return emptyMap()
    val wanted = ids.distinct()
    val out = HashMap<String, Stop>(wanted.size)
    wanted.chunked(500) { chunk ->
        val placeholders = chunk.joinToString(",") { "?" }
        rawQuery(
            "SELECT id, name, lat, lon, location_type, parent_station, wheelchair FROM stops WHERE id IN ($placeholders)",
            chunk.toTypedArray(),
        ).use { c -> while (c.moveToNext()) { val s = c.toStop(); out[s.id] = s } }
    }
    return out
}

data class ServiceDay(val date: String, val serviceIds: List<String>)

/** Service ids running on [date] (yyyyMMdd), honouring calendar + exceptions. */
fun SQLiteDatabase.serviceIdsOn(date: String): List<String> {
    val out = ArrayList<String>(64)
    rawQuery(
        "SELECT service_id, days_mask FROM calendar WHERE start_date <= ? AND end_date >= ?",
        arrayOf(date, date),
    ).use { c ->
        val dow = dayOfWeekIndex(date)
        while (c.moveToNext()) {
            if ((c.getInt(1) and (1 shl dow)) != 0) out.add(c.getString(0))
        }
    }
    val removed = HashSet<String>()
    rawQuery(
        "SELECT service_id FROM calendar_dates WHERE date = ? AND exception_type = 2",
        arrayOf(date),
    ).use { c -> while (c.moveToNext()) removed.add(c.getString(0)) }
    rawQuery(
        "SELECT service_id FROM calendar_dates WHERE date = ? AND exception_type = 1",
        arrayOf(date),
    ).use { c -> while (c.moveToNext()) if (c.getString(0) !in removed) out.add(c.getString(0)) }

    return out.filter { it !in removed }
}

/** GTFS uses sunday=1..saturday=7 as bit index 0..6. */
fun dayOfWeekIndex(gtfsDate: String): Int = when (gtfsDate.takeLast(2).toInt()) {
    1 -> 0
    2 -> 1
    3 -> 2
    4 -> 3
    5 -> 4
    6 -> 5
    else -> 6
}

fun SQLiteDatabase.meta(key: String): String? {
    rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
        return if (c.moveToFirst()) c.getString(0) else null
    }
}

fun SQLiteDatabase.putMeta(key: String, value: String) {
    execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)", arrayOf(key, value))
}

private class LatLonRadius(val lat: Double, val lon: Double, val radius: Double) {
    fun distanceTo(lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat)
        val dLon = Math.toRadians(lon2 - lon)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}

/** Great-circle distance in metres. */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}