package fr.tear36.blus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Decodes a real GTFS-RT Alerts capture when one is available.
 *
 * Set `BLUS_TEST_ALERTS` to a `.pb` downloaded from
 * `proxy.transport.data.gouv.fr/resource/naolib-nantes-gtfs-rt-alerts`.
 */
class GtfsRtAlertsTest {

    private val alertsFile: File? =
        System.getenv("BLUS_TEST_ALERTS")?.let { path ->
            val f = File(path)
            if (f.isFile && f.length() > 0) f else null
        }

    @Test
    fun `decodes a real Naolib alerts feed`() {
        val file = alertsFile
        assumeTrue("BLUS_TEST_ALERTS not set", file != null)
        val feed = GtfsRt.parse(file!!.readBytes())

        println("alerts feed: timestamp=${feed.timestamp} alerts=${feed.alerts.size} " +
            "trips=${feed.trips.size} vehicles=${feed.vehicles.size}")

        assertTrue("flux d'alertes vide", feed.alerts.isNotEmpty())
        assertTrue("timestamp du flux absent", feed.timestamp > 0)

        val withText = feed.alerts.filter { it.header.isNotBlank() || it.description.isNotBlank() }
        println("alertes avec texte=${withText.size}/${feed.alerts.size}")
        withText.take(3).forEach {
            println("  header=\"${it.header.take(70)}\" cause=${it.cause} severity=${it.severity} routes=${it.routes.size}")
        }
        assertTrue(
            "aucun libellé lisible dans le flux (${withText.size}/${feed.alerts.size})",
            withText.size > feed.alerts.size / 3,
        )

        for (a in feed.alerts) {
            assertNotNull(a.id)
            assertTrue("identifiant d'alerte vide", a.id.isNotBlank())
            assertTrue(
                "fenetre de validite incoherente: start=${a.start} end=${a.end}",
                a.start == null || a.end == null || a.end >= a.start,
            )
            for (text in listOf(a.header, a.description)) {
                assertTrue("marqueur HTML laissé en place: ${text.take(60)}", !text.contains('<'))
                assertTrue("caractère de remplacement (mojibake): ${text.take(60)}", !text.contains('\uFFFD'))
            }
            a.routes.forEach { assertTrue("route inattendue: $it", it.startsWith("FR_NAOLIB:")) }
            a.stops.forEach { assertTrue("arrêt inattendu: $it", it.startsWith("FR_NAOLIB:")) }
        }

        // The producer labels every translation `unspecified`, so a French-looking title
        // must be recovered anyway.
        assertTrue(
            "aucune traduction française trouvée",
            withText.any { it.header.any { c -> c.code > 127 } || it.description.any { c -> c.code > 127 } },
        )
    }

    @Test
    fun `maps alert enums to labels`() {
        assertEquals("travaux", GtfsRt.causeLabel(10))
        assertEquals("accident", GtfsRt.causeLabel(6))
        assertEquals("météo", GtfsRt.causeLabel(8))
        assertEquals("déviation", GtfsRt.effectLabel(4))
        assertEquals("service réduit", GtfsRt.effectLabel(2))
        assertEquals("retards importants", GtfsRt.effectLabel(3))
        assertEquals("important", GtfsRt.severityLabel(4))
        assertEquals("attention", GtfsRt.severityLabel(3))
        assertEquals("info", GtfsRt.severityLabel(2))
        assertEquals("cause inconnue", GtfsRt.causeLabel(99))
    }

    @Test
    fun `strips html and decodes entities`() {
        fun clean(s: String) = with(GtfsRt) { s.toUserText() }

        assertEquals("De lundi 31 août au vendredi 19 septembre", clean("<h4>De lundi 31 août au vendredi 19 septembre</h4>"))
        assertEquals("Travaux quais &amp; pont".replace("&amp;", "&"), clean("Travaux quais &amp; pont"))
        assertEquals("Travaux du 1°er au 15/09", clean("Travaux du 1&#176;er au 15/09"))
        assertEquals("A", clean("&#x41;"))
        assertEquals("test 123", clean("test\n   123"))
        assertEquals("", clean("   <br/>  "))
    }
}