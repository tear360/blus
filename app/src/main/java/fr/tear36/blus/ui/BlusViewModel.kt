package fr.tear36.blus.ui

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.tear36.blus.data.FeedState
import fr.tear36.blus.data.RealtimeSnapshot
import fr.tear36.blus.data.Route
import fr.tear36.blus.data.Stop
import fr.tear36.blus.data.TransitRepository
import fr.tear36.blus.data.TransitRepository.Departure
import fr.tear36.blus.data.searchStops
import fr.tear36.blus.data.routesAtStop
import fr.tear36.blus.data.stationsNear
import fr.tear36.blus.data.toStop
import fr.tear36.blus.update.AvailableUpdate
import fr.tear36.blus.update.UpdateChecker
import fr.tear36.blus.update.UpdateResult
import fr.tear36.blus.BlusApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LatLon(val lat: Double, val lon: Double)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val update: AvailableUpdate) : UpdateState()
    data class Downloading(val read: Long, val total: Long) : UpdateState()
    data class Ready(val filePath: String, val versionName: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class BlusViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TransitRepository(app)
    private val updater = UpdateChecker(app)

    val feedState = MutableStateFlow<FeedState>(FeedState.Idle)
    val realtime = MutableStateFlow(RealtimeSnapshot(emptyList(), emptyList(), 0, 0, 0))
    val location = MutableStateFlow<LatLon?>(null)
    val routes = MutableStateFlow<Map<String, Route>>(emptyMap())
    val selectedStopId = MutableStateFlow<String?>(null)
    val favorites = MutableStateFlow<Set<String>>(loadFavorites())
    val updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val searchResults = MutableStateFlow<List<Stop>>(emptyList())
    val lastError = MutableStateFlow<String?>(null)

    private var pollJob: Job? = null
    private var locationJob: Job? = null

    val repoRef: TransitRepository get() = repo

    init {
        viewModelScope.launch {
            repo.bootstrap(onState = { feedState.value = it })
            loadRoutes()
            startPolling()
            if (shouldAutoCheckUpdate()) checkUpdate(silent = true)
        }
    }

    private fun loadRoutes() {
        viewModelScope.launch(Dispatchers.IO) {
            routes.value = repo.routes()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = repo.realtimeFlow(20_000L)
            .catch { e -> lastError.value = e.message }
            .onEach { realtime.value = it }
            .launchIn(viewModelScope)
    }

    fun refreshRealtimeNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val snap = repo.poll()
            realtime.value = snap
        }
    }

    fun forceFeedRefresh() {
        viewModelScope.launch {
            feedState.value = FeedState.Downloading(0, -1)
            val ok = repo.bootstrap({ feedState.value = it }, force = true)
            if (ok) loadRoutes()
        }
    }

    @SuppressLint("MissingPermission")
    fun bindLocation(fused: com.google.android.gms.location.FusedLocationProviderClient) {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            try {
                fused.lastLocation
                    .addOnSuccessListener { loc: Location? ->
                        if (loc != null) location.value = LatLon(loc.latitude, loc.longitude)
                    }
                    .addOnFailureListener { /* permission refusÃ©e */ }
            } catch (e: SecurityException) {
                lastError.value = "Autorisation de localisation refusÃ©e"
            }
        }
    }

    fun pushLocation(lat: Double, lon: Double) {
        location.value = LatLon(lat, lon)
    }

    suspend fun nearbyStops(radiusMeters: Int = 1500, limit: Int = 40): List<Stop> {
        val p = location.value ?: return emptyList()
        return withContext(Dispatchers.IO) { repo.database.stationsNear(p.lat, p.lon, radiusMeters, limit) }
    }

    suspend fun nearestStation(): Stop? {
        val p = location.value ?: return defaultStation()
        return withContext(Dispatchers.IO) { repo.database.stationsNear(p.lat, p.lon, 3000, 1).firstOrNull() }
    }

    /** Commerce, centre de Nantes â€” used before the first location fix. */
    suspend fun defaultStation(): Stop? = withContext(Dispatchers.IO) {
        repo.database.stationsNear(47.2145, -1.5560, 700, 1).firstOrNull()
    }

    fun search(query: String) {
        if (query.length < 2) {
            searchResults.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            searchResults.value = repo.database.searchStops(query, 40)
        }
    }

    /** Blocking variant for callers already on a background dispatcher. */
    suspend fun searchNow(query: String): List<Stop> = withContext(Dispatchers.IO) {
        if (query.length < 2) {
            emptyList()
        } else {
            repo.database.searchStops(query, 40)
        }
    }

    suspend fun stop(id: String): Stop? = withContext(Dispatchers.IO) {
        repo.database.rawQuery(
            "SELECT id, name, lat, lon, location_type, parent_station, wheelchair FROM stops WHERE id = ?",
            arrayOf(id),
        ).use { c -> if (c.moveToFirst()) c.toStop() else null }
    }

    suspend fun departures(stopId: String, max: Int = 12): List<Departure> =
        withContext(Dispatchers.IO) { repo.departures(stopId, realtime.value, max) }

    suspend fun liveDepartures(stopId: String, max: Int = 8): List<Departure> =
        withContext(Dispatchers.IO) { repo.liveDepartures(stopId, realtime.value, max) }

    suspend fun routesAt(stopId: String): List<Route> = withContext(Dispatchers.IO) {
        repo.database.routesAtStop(stopId).mapNotNull { routes.value[it] }
    }

    fun vehiclesNear(center: LatLon, radiusMeters: Double): List<fr.tear36.blus.data.Vehicle> {
        val r2 = radiusMeters * radiusMeters
        return realtime.value.vehicles.filter { v ->
            val lat = v.lat ?: return@filter false
            val lon = v.lon ?: return@filter false
            val dLat = (lat - center.lat) * 111_320.0
            val dLon = (lon - center.lon) * 111_320.0 * Math.cos(Math.toRadians(center.lat))
            dLat * dLat + dLon * dLon <= r2
        }
    }

    // ---------- Favorites ----------

    private fun loadFavorites(): Set<String> =
        BlusApp.prefs(getApplication()).getStringSet(KEY_FAVORITES, emptySet()).orEmpty()

    fun toggleFavorite(stopId: String) {
        val current = favorites.value.toMutableSet()
        if (!current.add(stopId)) current.remove(stopId)
        BlusApp.prefs(getApplication()).edit().putStringSet(KEY_FAVORITES, current).apply()
        favorites.value = current
    }

    suspend fun favoriteStops(): List<Stop> = withContext(Dispatchers.IO) {
        favorites.value.mapNotNull { id ->
            repo.database.rawQuery(
                "SELECT id, name, lat, lon, location_type, parent_station, wheelchair FROM stops WHERE id = ?",
                arrayOf(id),
            ).use { c -> if (c.moveToFirst()) c.toStop() else null }
        }
    }

    // ---------- Mise Ã  jour ----------

    private fun shouldAutoCheckUpdate(): Boolean {
        val last = BlusApp.prefs(getApplication()).getLong(KEY_LAST_UPDATE_CHECK, 0L)
        val interval = BlusApp.prefs(getApplication()).getLong(KEY_UPDATE_INTERVAL, 12 * 3600_000L)
        return System.currentTimeMillis() - last > interval
    }

    fun checkUpdate(silent: Boolean = false) {
        viewModelScope.launch {
            updateState.value = UpdateState.Checking
            when (val r = updater.check()) {
                is UpdateResult.UpToDate -> updateState.value = UpdateState.UpToDate
                is UpdateResult.Available -> {
                    updateState.value = UpdateState.Available(r.update)
                    if (!silent) downloadUpdate(r.update)
                }
                is UpdateResult.Failed -> {
                    updateState.value = UpdateState.Error(r.reason)
                }
            }
        }
    }

    fun downloadUpdate(update: AvailableUpdate) {
        viewModelScope.launch {
            updateState.value = UpdateState.Downloading(0, update.sizeBytes)
            val file = updater.download(update) { read, total ->
                updateState.value = UpdateState.Downloading(read, total)
            }
            updateState.value = if (file != null) {
                UpdateState.Ready(file.absolutePath, update.versionName)
            } else {
                UpdateState.Error("tÃ©lÃ©chargement du APK impossible")
            }
        }
    }

    fun resetUpdateState() {
        updateState.value = UpdateState.Idle
    }

    override fun onCleared() {
        pollJob?.cancel()
        locationJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val KEY_FAVORITES = "favorite_stops"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_UPDATE_INTERVAL = "update_check_interval_ms"
    }
}