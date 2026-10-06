package fr.tear36.blus.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Streaming importer for the Naolib GTFS bundle.
 *
 * The archive is ~27 MB / 4.7M `stop_times` rows, so rows are parsed one at a time
 * inside a single transaction and never materialised as a whole.
 */
object GtfsImporter {

    data class Progress(val file: String, val rows: Long, val totalRows: Long) {
        val fraction: Float
            get() = if (totalRows <= 0) 0f else (rows.toFloat() / totalRows).coerceIn(0f, 1f)
    }

    data class ImportSummary(
        var stops: Int = 0,
        var routes: Int = 0,
        var trips: Int = 0,
        var stopTimes: Long = 0,
        var shapePoints: Long = 0,
    )

    private const val TOTAL_ROWS = 4_750_000L
    private const val PROGRESS_EVERY = 40_000

    suspend fun import(
        zip: File,
        db: SQLiteDatabase,
        onProgress: (Progress) -> Unit,
    ): ImportSummary = withContext(Dispatchers.IO) {
        val summary = ImportSummary()
        var rows = 0L

        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    rows = when (entry.name.substringAfterLast('/')) {
                        "stops.txt" -> importStops(zis, db, summary, rows, onProgress)
                        "routes.txt" -> importRoutes(zis, db, summary, rows, onProgress)
                        "trips.txt" -> importTrips(zis, db, summary, rows, onProgress)
                        "shapes.txt" -> importShapes(zis, db, summary, rows, onProgress)
                        "stop_times.txt" -> importStopTimes(zis, db, summary, rows, onProgress)
                        "calendar.txt" -> importCalendar(zis, db, rows, onProgress)
                        "calendar_dates.txt" -> importCalendarDates(zis, db, rows, onProgress)
                        else -> rows
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        onProgress(Progress("done", rows, TOTAL_ROWS))
        summary
    }

    private suspend fun importStops(
        zis: ZipInputStream, db: SQLiteDatabase, s: ImportSummary,
        rowsIn: Long, onProgress: (Progress) -> Unit,
    ): Long {
        val stmt = db.compileStatement(
            "INSERT INTO stops(id,name,lat,lon,location_type,parent_station,wheelchair) VALUES(?,?,?,?,?,?,?)",
        )
        var rows = rowsIn
        db.beginTransaction()
        try {
            stmt.use { st ->
                eachRow(zis) { f ->
                    st.clearBindings()
                    st.bindString(1, f[0])
                    st.bindString(2, f[2])
                    st.bindDouble(3, f[5].toDoubleOrNull() ?: 0.0)
                    st.bindDouble(4, f[6].toDoubleOrNull() ?: 0.0)
                    st.bindLong(5, f[9].toLongOrNull() ?: 0L)
                    if (f[10].isEmpty()) st.bindNull(6) else st.bindString(6, f[10])
                    st.bindLong(7, f[12].toLongOrNull() ?: 0L)
                    st.executeInsert()
                    s.stops++
                    rows++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onProgress(Progress("arrÃªts", rows, TOTAL_ROWS))
        return rows
    }

    private suspend fun importRoutes(
        zis: ZipInputStream, db: SQLiteDatabase, s: ImportSummary,
        rowsIn: Long, onProgress: (Progress) -> Unit,
    ): Long {
        val stmt = db.compileStatement(
            "INSERT INTO routes(id,short_name,long_name,mode,color,text_color,sort_order) VALUES(?,?,?,?,?,?,?)",
        )
        var rows = rowsIn
        db.beginTransaction()
        try {
            stmt.use { st ->
                eachRow(zis) { f ->
                    st.clearBindings()
                    st.bindString(1, f[0])
                    st.bindString(2, f[2])
                    if (f[3].isEmpty()) st.bindNull(3) else st.bindString(3, f[3])
                    st.bindLong(4, f[5].toLongOrNull() ?: 3L)
                    if (f[7].isEmpty()) st.bindNull(5) else st.bindString(5, f[7])
                    if (f[8].isEmpty()) st.bindNull(6) else st.bindString(6, f[8])
                    st.bindLong(7, f[9].toLongOrNull() ?: 0L)
                    st.executeInsert()
                    s.routes++
                    rows++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onProgress(Progress("lignes", rows, TOTAL_ROWS))
        return rows
    }

    private suspend fun importTrips(
        zis: ZipInputStream, db: SQLiteDatabase, s: ImportSummary,
        rowsIn: Long, onProgress: (Progress) -> Unit,
    ): Long {
        val stmt = db.compileStatement(
            "INSERT INTO trips(id,route_id,service_id,headsign,direction_id,shape_id) VALUES(?,?,?,?,?,?)",
        )
        var rows = rowsIn
        db.beginTransaction()
        try {
            stmt.use { st ->
                eachRow(zis) { f ->
                    st.clearBindings()
                    st.bindString(1, f[2])
                    st.bindString(2, f[0])
                    st.bindString(3, f[1])
                    if (f[3].isEmpty()) st.bindNull(4) else st.bindString(4, f[3])
                    st.bindLong(5, f[5].toLongOrNull() ?: 0L)
                    if (f[7].isEmpty()) st.bindNull(6) else st.bindString(6, f[7])
                    st.executeInsert()
                    s.trips++
                    rows++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onProgress(Progress("trajets", rows, TOTAL_ROWS))
        return rows
    }

    private suspend fun importShapes(
        zis: ZipInputStream, db: SQLiteDatabase, s: ImportSummary,
        rowsIn: Long, onProgress: (Progress) -> Unit,
    ): Long {
        val stmt = db.compileStatement(
            "INSERT INTO shapes(shape_id,seq,lat,lon) VALUES(?,?,?,?)",
        )
        var rows = rowsIn
        db.beginTransaction()
        try {
            stmt.use { st ->
                eachRow(zis) { f ->
                    st.clearBindings()
                    st.bindString(1, f[0])
                    st.bindLong(2, f[1].toLongOrNull() ?: 0L)
                    st.bindDouble(3, f[2].toDoubleOrNull() ?: 0.0)
                    st.bindDouble(4, f[3].toDoubleOrNull() ?: 0.0)
                    st.executeInsert()
                    s.shapePoints++
                    rows++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onProgress(Progress("tracÃ©s", rows, TOTAL_ROWS))
        return rows
    }

    private suspend fun importStopTimes(
        zis: ZipInputStream, db: SQLiteDatabase, s: ImportSummary,
        rowsIn: Long, onProgress: (Progress) -> Unit,
    ): Long {
        val stmt = db.compileStatement(
            "INSERT INTO stop_times(trip_id,seq,stop_id,arrival,departure) VALUES(?,?,?,?,?)",
        )
        var rows = rowsIn
        var last = 0L
        db.beginTransaction()
        try {
            stmt.use { st ->
                eachRow(zis) { f ->
                    val arrival = gtfsTimeToSec(f[1])
                    st.clearBindings()
                    st.bindString(1, f[0])
                    st.bindLong(2, f[4].toLongOrNull() ?: 0L)
                    st.bindString(3, f[3])
                    st.bindLong(4, arrival.toLong())
                    val departure = gtfsTimeToSec(f[2])
                    st.bindLong(5, (if (departure < 0) arrival else departure).toLong())
                    st.executeInsert()
                    s.stopTimes++
                    rows++
                    if (rows - last >= PROGRESS_EVERY) {
                        last = rows
                        onProgress(Progress("horaires", rows, TOTAL_ROWS))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onProgress(Progress("horaires", rows, TOTAL_ROWS))
        return rows
    }

    private suspend fun importCalendar(
        zis: ZipInputStream, db: SQLiteDatabase,
        rowsIn: Long, onProgress: (Progress) -> Unit,
    ): Long {
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO calendar(service_id,days_mask,start_date,end_date) VALUES(?,?,?,?)",
        )
        var rows = rowsIn
        db.beginTransaction()
        try {
            stmt.use { st ->
                eachRow(zis) { f ->
                    var mask = 0
                    for (i in 0..6) if (f[1 + i] == "1") mask = mask or (1 shl i)
                    st.clearBindings()
                    st.bindString(1, f[0])
                    st.bindLong(2, mask.toLong())
                    if (f[8].isEmpty()) st.bindNull(3) else st.bindString(3, f[8])
                    if (f[9].isEmpty()) st.bindNull(4) else st.bindString(4, f[9])
                    st.executeInsert()
                    rows++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onProgress(Progress("calendrier", rows, TOTAL_ROWS))
        return rows
    }

    private suspend fun importCalendarDates(
        zis: ZipInputStream, db: SQLiteDatabase,
        rowsIn: Long, onProgress: (Progress) -> Unit,
    ): Long {
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO calendar_dates(service_id,date,exception_type) VALUES(?,?,?)",
        )
        var rows = rowsIn
        db.beginTransaction()
        try {
            stmt.use { st ->
                eachRow(zis) { f ->
                    st.clearBindings()
                    st.bindString(1, f[0])
                    st.bindString(2, f[1])
                    st.bindLong(3, f[2].toLongOrNull() ?: 0L)
                    st.executeInsert()
                    rows++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onProgress(Progress("calendrier", rows, TOTAL_ROWS))
        return rows
    }

    // ---------- CSV ----------

    private const val MAX_FIELDS = 32

    /**
     * Streams every record of [zis] (header skipped) into [consumer].
     * The field array is reused across rows, so [consumer] must not retain it.
     */
    private suspend inline fun eachRow(
        zis: ZipInputStream,
        consumer: (Array<String>) -> Unit,
    ) {
        val reader = BufferedReader(InputStreamReader(zis, StandardCharsets.UTF_8), 1 shl 16)
        if (reader.readLine() == null) return // empty entry

        val fields = Array(MAX_FIELDS) { "" }
        val line = StringBuilder(256)
        val unquoted = StringBuilder(128)
        var counter = 0L

        while (true) {
            if (!readCsvRecord(reader, line)) break
            if (line.isEmpty()) continue

            val len = line.length
            var count = 0
            var i = 0
            while (i < len && count < MAX_FIELDS) {
                if (line[i] == '"') {
                    i++
                    unquoted.setLength(0)
                    while (i < len) {
                        val c = line[i]
                        if (c == '"') {
                            if (i + 1 < len && line[i + 1] == '"') {
                                unquoted.append('"'); i += 2
                            } else { i++; break }
                        } else { unquoted.append(c); i++ }
                    }
                    while (i < len && line[i] != ',') i++
                    fields[count++] = unquoted.toString()
                } else {
                    val start = i
                    while (i < len && line[i] != ',') i++
                    fields[count++] = line.substring(start, i)
                }
                if (i < len) i++ // skip the comma
            }
            while (count < MAX_FIELDS) fields[count++] = ""

            consumer(fields)

            if (++counter % 50_000L == 0L) coroutineContext.ensureActive()
        }
    }

    /** Reads one CSV record into [out] (quoted newlines allowed). Returns false at EOF. */
    private fun readCsvRecord(reader: BufferedReader, out: StringBuilder): Boolean {
        out.setLength(0)
        var c = reader.read()
        if (c == -1) return false
        var inQuotes = false
        while (c != -1) {
            val ch = c.toChar()
            if (ch == '"') inQuotes = !inQuotes
            if (ch == '\n' && !inQuotes) {
                if (out.isNotEmpty() && out[out.length - 1] == '\r') out.deleteCharAt(out.length - 1)
                if (out.isNotEmpty() && out[out.length - 1] == '\n') out.deleteCharAt(out.length - 1)
                return true
            }
            out.append(ch)
            c = reader.read()
        }
        if (out.isNotEmpty() && out[out.length - 1] == '\r') out.deleteCharAt(out.length - 1)
        return true
    }

    fun gtfsTimeToSec(t: String): Int {
        if (t.length < 8) return -1
        val h = t.substring(0, 2).toIntOrNull() ?: return -1
        val m = t.substring(3, 5).toIntOrNull() ?: return -1
        val s = t.substring(6, 8).toIntOrNull() ?: return -1
        return h * 3600 + m * 60 + s
    }
}