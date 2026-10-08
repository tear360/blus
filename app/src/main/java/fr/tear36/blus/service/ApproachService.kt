package fr.tear36.blus.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import fr.tear36.blus.BlusApp
import fr.tear36.blus.R
import fr.tear36.blus.data.TransitRepository
import fr.tear36.blus.data.haversineMeters
import fr.tear36.blus.data.quayIdsFor
import fr.tear36.blus.data.stopsById
import fr.tear36.blus.data.tripStopIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the live feed in the background and warns as soon as a vehicle serving one of the
 * user's favourite stops is a single stop away — the moment where you still have time to walk.
 */
class ApproachService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: TransitRepository

    private val tripStopsCache = HashMap<String, List<String>>(64)
    private val notified = HashMap<String, Long>(16)
    private var routeNames: Map<String, String> = emptyMap()

    private var lastLat: Double? = null
    private var lastLon: Double? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLat = loc.latitude
            lastLon = loc.longitude
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = TransitRepository(this)
        routeNames = repo.routes().mapValues { it.value.shortName }
        createChannel()
        ServiceCompat.startForeground(
            this,
            FOREGROUND_ID,
            foregroundNotification(),
            if (Build.VERSION.SDK_INT >= 34) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
        startLocationUpdates()
        scope.launch { watchLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        runCatching {
            LocationServices.getFusedLocationProviderClient(this)
                .removeLocationUpdates(locationCallback)
        }
        super.onDestroy()
    }

    // ---------- Watch loop ----------

    private suspend fun watchLoop() {
        while (scope.isActive) {
            runCatching { evaluate() }
                .onFailure { Log.w(TAG, "approach check failed", it) }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun evaluate() {
        val favorites = BlusApp.prefs(this).getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
        if (favorites.isEmpty()) return

        val database = repo.database
        val quays = HashMap<String, FavQuay>(64)
        for (stopId in favorites) {
            val stop = database.stopsById(listOf(stopId))[stopId] ?: continue
            val target = FavQuay(stop.name, stop.lat, stop.lon)
            for (quay in database.quayIdsFor(stopId)) quays[quay] = target
        }
        if (quays.isEmpty()) return

        val userLat = lastLat ?: return
        val userLon = lastLon ?: return
        val snapshot = runCatching { repo.poll() }.getOrNull() ?: return
        val now = System.currentTimeMillis()

        for (vehicle in snapshot.vehicles) {
            val next = vehicle.nextStopId ?: continue
            val sequence = tripStops(vehicle.tripId)
            val index = sequence.indexOf(next)
            if (index < 0) continue
            // The stop right after the next one is the warning window.
            val upcoming = sequence.getOrNull(index + 1) ?: continue
            val target = quays[upcoming] ?: continue
            if (haversineMeters(userLat, userLon, target.lat, target.lon) > NOTIFY_RADIUS_METERS) {
                continue
            }
            val key = "${vehicle.tripId}|$upcoming"
            val last = notified[key] ?: 0L
            if (now - last < COOLDOWN_MS) continue
            notified[key] = now
            mainHandler.post { announce(vehicle, target) }
        }

        if (notified.size > 256) {
            val limit = now - COOLDOWN_MS
            notified.entries.removeAll { it.value < limit }
        }
    }

    private fun tripStops(tripId: String): List<String> =
        tripStopsCache.getOrPut(tripId) {
            if (tripStopsCache.size > 400) tripStopsCache.clear()
            runCatching { repo.database.tripStopIds(tripId) }.getOrDefault(emptyList())
        }

    // ---------- Notification + vibration ----------

    private fun announce(vehicle: fr.tear36.blus.data.Vehicle, target: FavQuay) {
        val line = routeNames[vehicle.routeId].orEmpty().ifBlank { "Bus" }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle("$line à 1 arrêt de ${target.name}")
            .setContentText(vehicle.headsign.ifBlank { "Direction inconnue" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(target.name.hashCode() xor vehicle.tripId.hashCode(), notification)
        vibrateStrongly()
    }

    @Suppress("DEPRECATION")
    private fun vibrateStrongly() {
        val pattern = longArrayOf(0, 400, 150, 400, 150, 800)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
        val effect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        runCatching { vibrator.vibrate(effect) }
    }

    // ---------- Plumbing ----------

    private fun startLocationUpdates() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(10_000L)
            .build()
        runCatching {
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alertes d’approche",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Prévient quand un bus favori est à un arrêt"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 800)
        }
        manager.createNotificationChannel(channel)
    }

    private fun foregroundNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle("Surveillance des favoris")
            .setContentText("Blus surveille vos arrêts favoris")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()

    private data class FavQuay(val name: String, val lat: Double, val lon: Double)

    companion object {
        private const val TAG = "Blus/Approach"
        private const val CHANNEL_ID = "approach"
        private const val FOREGROUND_ID = 42
        private const val POLL_INTERVAL_MS = 20_000L
        private const val COOLDOWN_MS = 5 * 60_000L
        private const val NOTIFY_RADIUS_METERS = 600.0
        private const val KEY_FAVORITES = "favorite_stops"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ApproachService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ApproachService::class.java))
        }
    }
}
