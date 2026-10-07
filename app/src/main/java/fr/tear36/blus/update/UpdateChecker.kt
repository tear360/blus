package fr.tear36.blus.update

import android.content.Context
import android.util.Log
import fr.tear36.blus.BuildConfig
import fr.tear36.blus.data.BuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class GhAsset(
    val name: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String? = null,
    val size: Long = 0,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("assets") val assets: List<GhAsset> = emptyList(),
)

data class AvailableUpdate(
    val tagName: String,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val publishedAt: String,
)

sealed class UpdateResult {
    object UpToDate : UpdateResult()
    data class Available(val update: AvailableUpdate) : UpdateResult()
    data class Failed(val reason: String) : UpdateResult()
}

/**
 * Checks the latest GitHub release for this repository and offers in-app updates.
 *
 * The install step is delegated to the system package installer, which is the only
 * mechanism allowed on a stock Android install (no root, no `adb`).
 */
class UpdateChecker(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(BuildInfo.RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Blus-${BuildConfig.VERSION_NAME}")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateResult.Failed("GitHub a répondu ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val release = try {
                    json.decodeFromString(GhRelease.serializer(), body)
                } catch (e: Exception) {
                    return@withContext UpdateResult.Failed("réponse illisible")
                }

                if (release.draft || release.tagName.isBlank()) {
                    return@withContext UpdateResult.Failed("aucune version publiée")
                }
                if (release.prerelease && !allowPrerelease) {
                    return@withContext UpdateResult.Failed("seule une version bêta est disponible")
                }

                val apk = release.assets.firstOrNull { asset ->
                    val n = asset.name?.lowercase().orEmpty()
                    n.endsWith(".apk") && !n.contains("debug") && !n.contains("sources")
                } ?: release.assets.firstOrNull { it.name?.lowercase()?.endsWith(".apk") == true }
                    ?: return@withContext UpdateResult.Failed("aucun APK dans la release")

                val remoteVersion = release.tagName.removePrefix("v")
                if (compareVersions(remoteVersion, BuildConfig.VERSION_NAME) <= 0) {
                    return@withContext UpdateResult.UpToDate
                }

                UpdateResult.Available(
                    AvailableUpdate(
                        tagName = release.tagName,
                        versionName = remoteVersion,
                        notes = release.body?.take(1200).orEmpty(),
                        apkUrl = apk.downloadUrl.orEmpty(),
                        sizeBytes = apk.size,
                        publishedAt = release.publishedAt.orEmpty(),
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            UpdateResult.Failed(e.message ?: "réseau indisponible")
        }
    }

    /** Downloads the APK into app-private storage and returns the file. */
    suspend fun download(update: AvailableUpdate, onProgress: (read: Long, total: Long) -> Unit): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "updates").apply { mkdirs() }
                val target = File(dir, "blus-${update.versionName}.apk")
                val request = Request.Builder()
                    .url(update.apkUrl)
                    .header("Accept", "application/vnd.android.package-archive")
                    .header("User-Agent", "Blus-${BuildConfig.VERSION_NAME}")
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    val total = body.contentLength()
                    target.outputStream().buffered(1 shl 16).use { out ->
                        body.byteStream().use { input ->
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
                if (target.length() > 0) target else null
            } catch (e: Exception) {
                Log.w(TAG, "apk download failed", e)
                null
            }
        }

    companion object {
        private const val TAG = "Blus/Update"
        var allowPrerelease = false

        /** Compares dotted numeric versions; non-numeric suffixes are ignored. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val pb = b.split('.', '-').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val va = pa.getOrNull(i) ?: 0
                val vb = pb.getOrNull(i) ?: 0
                if (va != vb) return va.compareTo(vb)
            }
            return 0
        }
    }
}