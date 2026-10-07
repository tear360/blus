package fr.tear36.blus.data

import fr.tear36.blus.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP access to the open Naolib feeds.
 *
 * Every endpoint is public and published by Nantes Métropole / the French national
 * access point for transport open data — no API key is required.
 */
class NetworkClient(private val cacheDir: File) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val userAgent = "Blus/${BuildConfig.VERSION_NAME} (Android; open-data transit app)"

    // GTFS static bundle — ~27 MB, refreshed every few days.
    private val gtfsUrl = "https://data.nantesmetropole.fr/explore/dataset/" +
        "244400404_transports_commun_naolib_nantes_metropole_gtfs/files/" +
        "0cc0469a72de54ee045cb66d1a21de9e/download/"

    // GTFS-RT real time (prochains passages + perturbations), proxied by the PAN.
    private val rtTripUpdateUrl = "https://proxy.transport.data.gouv.fr/resource/naolib-nantes-gtfs-rt-trip-update"
    private val rtAlertsUrl = "https://proxy.transport.data.gouv.fr/resource/naolib-nantes-gtfs-rt-alerts"

    class HttpFailure(val code: Int, message: String) : IOException(message)

    /** Downloads the GTFS archive, reporting progress in bytes. */
    suspend fun downloadGtfs(
        onProgress: (bytesRead: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = File(cacheDir, "gtfs-download.zip")
        val tmp = File(cacheDir, "gtfs-download.zip.part")
        val request = Request.Builder().url(gtfsUrl).header("User-Agent", userAgent).build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code, "GTFS HTTP ${response.code}")
            val total = response.body?.contentLength() ?: -1L
            response.body!!.byteStream().use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
        if (tmp.renameTo(target)) target else tmp.also { it.renameTo(target) }
    }

    suspend fun fetchTripUpdates(): ByteArray = withContext(Dispatchers.IO) {
        getBinary(rtTripUpdateUrl)
    }

    suspend fun fetchAlerts(): ByteArray = withContext(Dispatchers.IO) {
        getBinary(rtAlertsUrl)
    }

    /** Cheap reachability probe for the real-time feed. */
    suspend fun pingRealTime(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(rtTripUpdateUrl).header("User-Agent", userAgent).build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    private fun getBinary(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/x-protobuf,application/octet-stream,*/*")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code, "HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw IOException("corps de réponse vide")
            if (bytes.isEmpty()) throw IOException("flux temps réel vide")
            return bytes
        }
    }
}

object BuildInfo {
    const val REPO = "tear360/blus"
    const val RELEASES_API = "https://api.github.com/repos/$REPO/releases/latest"
}