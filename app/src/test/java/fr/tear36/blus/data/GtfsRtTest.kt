package fr.tear36.blus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Decodes a real GTFS-RT capture when one is available.
 *
 * Set `BLUS_TEST_FEED` to a `.pb` downloaded from
 * `proxy.transport.data.gouv.fr/resource/naolib-nantes-gtfs-rt-trip-update`.
 * Tests are skipped (not failed) when the file is absent so CI stays green.
 */
class GtfsRtTest {

    private val feedFile: File? =
        System.getenv("BLUS_TEST_FEED")?.let { path ->
            val f = File(path)
            if (f.isFile && f.length() > 0) f else null
        }

    @Test
    fun `decodes a real Naolib trip-update feed`() {
        val file = feedFile
        assumeTrue("BLUS_TEST_FEED not set", file != null)
        val feed = GtfsRt.parse(file!!.readBytes())

        println("feed timestamp=${feed.timestamp} trips=${feed.trips.size} " +
            "vehicles=${feed.vehicles.size} alerts=${feed.alerts.size}")

        assertTrue("la capture ne contient aucun trajet", feed.trips.isNotEmpty())
        assertTrue("timestamp GTFS-RT absent", feed.timestamp > 1_600_000_000L)

        val t = feed.trips.first()
        println("sample trip: id=${t.tripId} route=${t.routeId} calls=${t.calls.size}")
        assertTrue("tripId vide", t.tripId.startsWith("FR_NAOLIB:VehicleJourney:"))
        assertTrue("routeId vide", t.routeId.startsWith("FR_NAOLIB:Line:"))
        assertTrue("aucun passage annoncé", t.calls.isNotEmpty())

        val call = t.calls.first()
        println("sample call: stop=${call.stopId} seq=${call.stopSequence} time=${call.arrivalTime}")
        assertTrue("stopId vide", call.stopId!!.startsWith("FR_NAOLIB:Quay:"))
        assertTrue("stop_sequence absent", call.stopSequence > 0)
        assertTrue("horaire absent", call.arrivalTime > 1_600_000_000L)

        // Every trip must reference a real route, otherwise the map shows no colour.
        assertTrue(
            "certains trips n'ont pas de routeId",
            feed.trips.all { it.routeId.startsWith("FR_NAOLIB:Line:") },
        )
    }

    @Test
    fun `tolerates truncated and garbage payloads`() {
        val good = feedFile?.readBytes() ?: ByteArray(0)
        // Truncated stream.
        if (good.isNotEmpty()) {
            val truncated = good.copyOfRange(0, good.size / 3)
            val feed = GtfsRt.parse(truncated)
            assertNotNull(feed)
        }
        // Random bytes must not throw.
        val garbage = ByteArray(512) { (it * 31 % 251).toByte() }
        assertNotNull(GtfsRt.parse(garbage))
        // Empty payload.
        assertEquals(0, GtfsRt.parse(ByteArray(0)).trips.size)
    }

    @Test
    fun `decodes a synthetic vehicle position`() {
        // Hand-built protobuf: FeedMessage{ entity{ vehicle{ trip, position{lat,lon} } } }
        val pb = PbBuilder()
            .message(1) { it.raw(ByteArray(0)) }                 // header (empty)
            .message(2) {                                     // entity
                it.string(1, "V")                             // id
                it.message(4) { v ->                          // vehicle
                    v.message(1) { t -> t.string(1, "FR_XXX") } // trip{ trip_id }
                    v.message(2) { pos ->                      // position
                        pos.float32(1, 47.2f)
                        pos.float32(2, -1.55f)
                    }
                }
            }
            .build()

        val feed = GtfsRt.parse(pb)
        assertEquals(1, feed.vehicles.size)
        val v = feed.vehicles.first()
        assertEquals("FR_XXX", v.tripId)
        assertTrue("latitude hors Nantes", Math.abs(v.lat - 47.2) < 0.001)
        assertTrue("longitude hors Nantes", Math.abs(v.lon + 1.55) < 0.001)
    }

    @Test
    fun `maps enums to French labels`() {
        assertEquals("travaux", GtfsRt.causeLabel(10))
        assertEquals("accident", GtfsRt.causeLabel(6))
        assertEquals("déviation", GtfsRt.effectLabel(4))
        assertEquals("service réduit", GtfsRt.effectLabel(2))
        assertEquals("important", GtfsRt.severityLabel(4))
    }
}