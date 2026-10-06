package fr.tear36.blus.data

/**
 * Minimal GTFS-Realtime (protobuf) reader.
 *
 * Only the subset published by the Naolib feed is decoded: `TripUpdate` (prochains passages)
 * and `Alert` (perturbations). `VehiclePosition` is decoded too even though the producer
 * currently leaves it empty, so the app starts working the day they publish it.
 */
object GtfsRt {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    private class Reader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        fun isDone(): Boolean = pos >= end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < end) {
                val b = buf[pos++].toInt()
                result = result or ((b.toLong() and 0x7FL) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) break
            }
            return result
        }

        fun readInt(): Int = readVarint().toInt()

        fun readString(): String {
            val len = readVarint().toInt()
            if (len <= 0) return ""
            val s = String(buf, pos, minOf(len, end - pos), Charsets.UTF_8)
            pos += len
            return s
        }

        fun readFloat(): Float {
            if (pos + 4 > end) { pos = end; return 0f }
            val bits = (buf[pos].toInt() and 0xFF) or
                ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or
                ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return Float.fromBits(bits)
        }

        fun readBytes(): Reader {
            val len = readVarint().toInt()
            if (len <= 0) return Reader(buf, pos, pos)
            val stop = minOf(pos + len, end)
            return Reader(buf, pos, stop)
        }

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> pos += 8
                WIRE_LEN -> pos += readVarint().toInt()
                WIRE_FIXED32 -> pos += 4
                else -> pos = end
            }
        }

        fun next(visitor: (Int, Int, Reader) -> Boolean): Boolean {
            if (pos >= end) return false
            val key = readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 0x7L).toInt()
            return visitor(field, wire, this)
        }
    }

    // ---------- Models ----------

    data class RtCall(
        val stopId: String?,
        val stopSequence: Int,
        val arrivalTime: Long,
        val departureTime: Long,
        val delay: Int,
    )

    data class RtTrip(
        val tripId: String,
        val routeId: String,
        val directionId: Int,
        val headsign: String,
        val timestamp: Long,
        val delay: Int,
        val scheduleRelationship: Int,
        val calls: List<RtCall>,
    )

    data class RtVehiclePosition(
        val tripId: String,
        val routeId: String,
        val lat: Double,
        val lon: Double,
        val bearing: Float?,
        val speedMps: Double?,
        val currentStatus: Int,
        val stopId: String?,
        val timestamp: Long,
    )

    data class RtAlert(
        val id: String,
        val header: String,
        val description: String,
        val cause: Int,
        val effect: Int,
        val severity: Int,
        val url: String?,
        val routes: List<String>,
        val stops: List<String>,
        val start: Long?,
        val end: Long?,
    )

    data class Feed(
        val timestamp: Long,
        val trips: List<RtTrip>,
        val vehicles: List<RtVehiclePosition>,
        val alerts: List<RtAlert>,
    )

    // ---------- Decoding ----------

    fun parse(data: ByteArray): Feed {
        val root = Reader(data, 0, data.size)
        var timestamp = 0L
        val trips = ArrayList<RtTrip>(512)
        val vehicles = ArrayList<RtVehiclePosition>(64)
        val alerts = ArrayList<RtAlert>(32)

        root.next { field, wire, r ->
            when {
                field == 1 && wire == WIRE_LEN -> {
                    r.readBytes().next { hf, hw, hr ->
                        if (hf == 3 && hw == WIRE_VARINT) timestamp = hr.readVarint()
                        else hr.skip(hw)
                        true
                    }
                }
                field == 2 && wire == WIRE_LEN -> {
                    val sub = r.readBytes()
                    var id = ""
                    sub.next { ef, ew, er ->
                        when {
                            ef == 1 && ew == WIRE_LEN -> { id = er.readString(); true }
                            ef == 3 && ew == WIRE_LEN -> { trips.add(parseTripUpdate(er.readBytes(), id)); true }
                            ef == 4 && ew == WIRE_LEN -> {
                                parseVehicle(er.readBytes(), id)?.let { vehicles.add(it) }
                                true
                            }
                            ef == 5 && ew == WIRE_LEN -> { alerts.add(parseAlert(er.readBytes(), id)); true }
                            else -> { er.skip(ew); true }
                        }
                    }
                }
                else -> { r.skip(wire); true }
            }
        }
        return Feed(timestamp, trips, vehicles, alerts)
    }

    private class TripDesc {
        var tripId = ""
        var routeId = ""
        var directionId = 0
        var headsign = ""
    }

    private fun parseTripDesc(r: Reader): TripDesc {
        val t = TripDesc()
        r.next { f, w, rr ->
            when {
                f == 1 && w == WIRE_LEN -> { t.tripId = rr.readString(); true }
                f == 5 && w == WIRE_LEN -> { t.routeId = rr.readString(); true }
                f == 6 && w == WIRE_VARINT -> { t.directionId = rr.readInt(); true }
                f == 8 && w == WIRE_LEN -> { t.headsign = rr.readString(); true }
                else -> { rr.skip(w); true }
            }
        }
        return t
    }

    private class Ev { var delay = 0; var time = 0L; var hasTime = false }

    private fun parseEvent(r: Reader): Ev {
        val e = Ev()
        r.next { f, w, rr ->
            when {
                f == 1 && w == WIRE_VARINT -> { e.delay = rr.readInt(); true }
                f == 2 && w == WIRE_VARINT -> { e.time = rr.readVarint(); e.hasTime = true; true }
                else -> { rr.skip(w); true }
            }
        }
        return e
    }

    private fun parseStopTimeUpdate(r: Reader): RtCall? {
        var stopId: String? = null
        var seq = 0
        var arr = Ev()
        var dep = Ev()
        r.next { f, w, rr ->
            when {
                f == 1 && w == WIRE_VARINT -> { seq = rr.readInt(); true }
                f == 2 && w == WIRE_LEN -> { stopId = rr.readString(); true }
                f == 3 && w == WIRE_LEN -> { arr = parseEvent(rr.readBytes()); true }
                f == 4 && w == WIRE_LEN -> { dep = parseEvent(rr.readBytes()); true }
                else -> { rr.skip(w); true }
            }
        }
        val time = when {
            arr.hasTime -> arr.time
            dep.hasTime -> dep.time
            else -> return null
        }
        return RtCall(stopId, seq, arr.time, dep.time, dep.delay.takeIf { it != 0 } ?: arr.delay)
    }

    private fun parseTripUpdate(r: Reader, entityId: String): RtTrip {
        var trip = TripDesc()
        var timestamp = 0L
        var delay = 0
        var rel = 0
        val calls = ArrayList<RtCall>(8)
        r.next { f, w, rr ->
            when {
                f == 1 && w == WIRE_LEN -> { trip = parseTripDesc(rr.readBytes()); true }
                f == 3 && w == WIRE_LEN -> { parseStopTimeUpdate(rr.readBytes())?.let { calls.add(it) }; true }
                f == 4 && w == WIRE_VARINT -> { timestamp = rr.readVarint(); true }
                f == 5 && w == WIRE_VARINT -> { delay = rr.readInt(); true }
                f == 6 && w == WIRE_VARINT -> { rel = rr.readInt(); true }
                f == 7 && w == WIRE_LEN -> { if (trip.tripId.isEmpty()) trip.tripId = rr.readString(); else rr.skip(w); true }
                else -> { rr.skip(w); true }
            }
        }
        val id = trip.tripId.ifEmpty { entityId }
        return RtTrip(id, trip.routeId, trip.directionId, trip.headsign, timestamp, delay, rel, calls)
    }

    private fun parseVehicle(r: Reader, entityId: String): RtVehiclePosition? {
        var trip = TripDesc()
        var lat = 0.0
        var lon = 0.0
        var bearing: Float? = null
        var speed: Double? = null
        var status = 0
        var stopId: String? = null
        var timestamp = 0L
        r.next { f, w, rr ->
            when {
                f == 1 && w == WIRE_LEN -> { trip = parseTripDesc(rr.readBytes()); true }
                f == 2 && w == WIRE_LEN -> {
                    rr.readBytes().next { pf, pw, pr ->
                        when {
                            pf == 1 && pw == WIRE_FIXED32 -> { lat = pr.readFloat().toDouble(); true }
                            pf == 2 && pw == WIRE_FIXED32 -> { lon = pr.readFloat().toDouble(); true }
                            pf == 3 && pw == WIRE_FIXED32 -> { bearing = pr.readFloat(); true }
                            pf == 5 && pw == WIRE_FIXED32 -> { speed = pr.readFloat().toDouble(); true }
                            else -> { pr.skip(pw); true }
                        }
                    }
                    true
                }
                f == 4 && w == WIRE_VARINT -> { status = rr.readInt(); true }
                f == 5 && w == WIRE_VARINT -> { timestamp = rr.readVarint(); true }
                f == 7 && w == WIRE_LEN -> { stopId = rr.readString(); true }
                else -> { rr.skip(w); true }
            }
        }
        if (lat == 0.0 && lon == 0.0) return null
        return RtVehiclePosition(
            tripId = trip.tripId.ifEmpty { entityId },
            routeId = trip.routeId,
            lat = lat,
            lon = lon,
            bearing = bearing,
            speedMps = speed,
            currentStatus = status,
            stopId = stopId,
            timestamp = timestamp,
        )
    }

    private fun parseTranslated(r: Reader): String {
        var out = ""
        r.next { f, w, rr ->
            if (f == 1 && w == WIRE_LEN) { out = rr.readString(); true } else { rr.skip(w); true }
        }
        return out
    }

    private fun parseAlert(r: Reader, entityId: String): RtAlert {
        var header = ""
        var description = ""
        var cause = 0
        var effect = 0
        var severity = 0
        var url: String? = null
        var start: Long? = null
        var end: Long? = null
        val routes = ArrayList<String>(4)
        val stops = ArrayList<String>(4)

        r.next { f, w, rr ->
            when {
                f == 1 && w == WIRE_LEN -> {
                    rr.readBytes().next { tf, tw, tr ->
                        when {
                            tf == 1 && tw == WIRE_VARINT -> { start = tr.readVarint(); true }
                            tf == 2 && tw == WIRE_VARINT -> { end = tr.readVarint(); true }
                            else -> { tr.skip(tw); true }
                        }
                    }
                    true
                }
                f == 5 && w == WIRE_LEN -> {
                    rr.readBytes().next { ef, ew, er ->
                        when {
                            ef == 2 && ew == WIRE_LEN -> { routes.add(er.readString()); true }
                            ef == 4 && ew == WIRE_LEN -> { er.skip(ew); true }
                            ef == 6 && ew == WIRE_LEN -> { stops.add(er.readString()); true }
                            else -> { er.skip(ew); true }
                        }
                    }
                    true
                }
                f == 6 && w == WIRE_VARINT -> { cause = rr.readInt(); true }
                f == 7 && w == WIRE_VARINT -> { effect = rr.readInt(); true }
                f == 8 && w == WIRE_LEN -> { url = rr.readString(); true }
                f == 10 && w == WIRE_LEN -> { header = parseTranslated(rr.readBytes()); true }
                f == 11 && w == WIRE_LEN -> { description = parseTranslated(rr.readBytes()); true }
                f == 14 && w == WIRE_VARINT -> { severity = rr.readInt(); true }
                else -> { rr.skip(w); true }
            }
        }
        return RtAlert(
            id = entityId,
            header = header,
            description = description,
            cause = cause,
            effect = effect,
            severity = severity,
            url = url,
            routes = routes,
            stops = stops,
            start = start,
            end = end,
        )
    }

    // ---------- Enum label helpers ----------

    val CAUSES = mapOf(
        1 to "cause inconnue", 2 to "autre cause", 3 to "problème technique",
        4 to "grève", 5 to "manifestation", 6 to "accident", 7 to "jour férié",
        8 to "météo", 9 to "maintenance", 10 to "travaux",
        11 to "intervention police", 12 to "urgence médicale",
    )

    val EFFECTS = mapOf(
        1 to "pas de service", 2 to "service réduit", 3 to "retards importants",
        4 to "déviation", 5 to "service additionnel", 6 to "service modifié",
        7 to "autre effet", 8 to "effet inconnu", 9 to "arrêt déplacé",
        10 to "pas d'impact", 11 to "problème d'accessibilité",
    )

    fun causeLabel(v: Int): String = CAUSES[v] ?: "cause inconnue"
    fun effectLabel(v: Int): String = EFFECTS[v] ?: "impact inconnu"
    fun severityLabel(v: Int): String = when (v) {
        2 -> "info"
        3 -> "attention"
        4 -> "important"
        else -> "info"
    }
}