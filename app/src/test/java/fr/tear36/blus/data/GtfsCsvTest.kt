package fr.tear36.blus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class GtfsCsvTest {

    private fun parse(csv: String): List<List<String>> {
        val reader = StringReader(csv)
        val fields = Array(GtfsCsv.DEFAULT_MAX_FIELDS) { "" }
        val line = StringBuilder(64)
        val out = ArrayList<List<String>>()
        while (true) {
            val n = GtfsCsv.readRecord(reader, line, fields)
            if (n < 0) break
            if (line.isNotEmpty()) out.add(fields.take(n).toList())
        }
        return out
    }

    @Test
    fun `parses a simple unquoted row`() {
        val rows = parse("a,b,c\n")
        assertEquals(1, rows.size)
        assertEquals(listOf("a", "b", "c"), rows[0])
    }

    @Test
    fun `does not emit a spurious trailing empty field`() {
        // Regression: an older parser appended one extra empty field for "a,b".
        assertEquals(listOf("a", "b"), parse("a,b\n")[0])
        assertEquals(listOf("a"), parse("a\n")[0])
        // ...but a line ending on a separator does have a final empty column.
        assertEquals(listOf("", ""), parse(",\n")[0])
        assertEquals(listOf("a", ""), parse("a,\n")[0])
    }

    @Test
    fun `strips surrounding quotes`() {
        val rows = parse("\"a\",\"b\"\n")
        assertEquals(listOf("a", "b"), rows[0])
    }

    @Test
    fun `keeps commas inside quotes`() {
        // Real case: route_long_name uses " / " separators and commas appear in some names.
        val rows = parse("\"Commerce, place\",\"x\"\n")
        assertEquals(listOf("Commerce, place", "x"), rows[0])
    }

    @Test
    fun `handles doubled quotes as an escaped quote`() {
        val rows = parse("\"He said \"\"hi\"\"\",\"b\"\n")
        assertEquals("He said \"hi\"", rows[0][0])
        assertEquals("b", rows[0][1])
    }

    @Test
    fun `handles a newline inside a quoted field`() {
        val rows = parse("\"line1\nline2\",\"b\"\n")
        assertEquals(1, rows.size)
        assertEquals("line1\nline2", rows[0][0])
    }

    @Test
    fun `strips a trailing carriage return`() {
        val rows = parse("a,b\r\nc,d\r\n")
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows[0])
        assertEquals(listOf("c", "d"), rows[1])
    }

@Test
fun `pads short rows with empty strings so indices stay stable`() {
        val reader = StringReader("a,b,c\nshort\n")
        val fields = Array(GtfsCsv.DEFAULT_MAX_FIELDS) { "STALE" }
        val line = StringBuilder(64)

        assertEquals(3, GtfsCsv.readRecord(reader, line, fields))
        assertEquals("a", fields[0])
        assertEquals("c", fields[2])

        // A row with fewer columns still leaves later columns addressable.
        assertEquals(1, GtfsCsv.readRecord(reader, line, fields))
        assertEquals("short", fields[0])
        assertEquals("", fields[3])
        assertEquals("", fields[fields.size - 1])
    }

    @Test
    fun `handles real Naolib stop_times rows`() {
        val csv = "trip_id,arrival_time,departure_time,stop_id,stop_sequence,stop_headsign,pickup_type,drop_off_type,shape_dist_traveled\n" +
            "\"FR_NAOLIB:VehicleJourney:49794053-CR_26_27-HW27BA17-Dimanche-00\",04:53:00,04:53:00,\"FR_NAOLIB:Quay:2769\",1,,0,0,\n" +
            "\"FR_NAOLIB:VehicleJourney:49794053-CR_26_27-HW27BA17-Dimanche-00\",04:56:00,04:56:00,\"FR_NAOLIB:Quay:138\",2,,0,0,\n"
        val rows = parse(csv).drop(1) // drop the header, as the importer does
        assertEquals(2, rows.size)
        assertEquals("FR_NAOLIB:Quay:2769", rows[0][3])
        assertEquals("1", rows[0][4])
        assertEquals("FR_NAOLIB:Quay:138", rows[1][3])
        assertEquals("2", rows[1][4])
        // 9 columns in the file; the trailing empty column must be preserved.
        assertEquals(9, rows[0].size)
        assertEquals(180, GtfsCsv.timeToSec(rows[1][1]) - GtfsCsv.timeToSec(rows[0][1]))
    }

    @Test
    fun `handles real Naolib routes rows with accented names`() {
        val csv = "route_id,agency_id,route_short_name,route_long_name,route_desc,route_type,route_url,route_color,route_text_color,route_sort_order\n" +
            "\"FR_NAOLIB:Line:1\",\"FR_NAOLIB:Operator:1\",\"1\",\"François Mitterrand / Jamet - Beaujoire\",,0,,007a45,ffffff,1\n"
        val rows = parse(csv).drop(1)
        assertEquals(1, rows.size)
        assertEquals("FR_NAOLIB:Line:1", rows[0][0])
        assertEquals("1", rows[0][2])
        assertEquals("007a45", rows[0][7])
        assertEquals(10, rows[0].size)
        assertTrue(rows[0][3].contains("Mitterrand"))
    }

    @Test
    fun `pads short rows so column indices stay stable`() {
        val reader = StringReader("a,b,c\nshort\n")
        val fields = Array(GtfsCsv.DEFAULT_MAX_FIELDS) { "STALE" }
        val line = StringBuilder(64)

        assertEquals(3, GtfsCsv.readRecord(reader, line, fields))
        assertEquals("a", fields[0])

        assertEquals(1, GtfsCsv.readRecord(reader, line, fields))
        assertEquals("short", fields[0])
        assertEquals("", fields[3])
        assertEquals("", fields[31])
    }

    @Test
    fun `converts GTFS times including past midnight services`() {
        assertEquals(0, GtfsCsv.timeToSec("00:00:00"))
        assertEquals(3600, GtfsCsv.timeToSec("01:00:00"))
        assertEquals(17_580, GtfsCsv.timeToSec("04:53:00"))
        // Naolib night services run past 24h, which must not wrap around.
        assertEquals(25 * 3600 + 10 * 60, GtfsCsv.timeToSec("25:10:00"))
        assertEquals(-1, GtfsCsv.timeToSec(""))
        assertEquals(-1, GtfsCsv.timeToSec("nope"))
    }

    @Test
    fun `splitLine handles the agency header shape`() {
        val header = "agency_id,agency_name,agency_url,agency_timezone,agency_lang,agency_phone,agency_fare_url,agency_email"
        assertEquals(8, GtfsCsv.splitLine(header).size)
        assertEquals("agency_id", GtfsCsv.splitLine(header)[0])
    }
}