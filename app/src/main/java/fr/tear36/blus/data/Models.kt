package fr.tear36.blus.data

/** A GTFS stop (Quay = physical platform, StopPlace = logical station). */
data class Stop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val locationType: Int,
    val parentStation: String?,
    val wheelchair: Int,
)

data class Route(
    val id: String,
    val shortName: String,
    val longName: String,
    val mode: Int,
    val color: String,
    val textColor: String,
    val sortOrder: Int,
)

data class Trip(
    val id: String,
    val routeId: String,
    val serviceId: String,
    val headsign: String,
    val directionId: Int,
    val shapeId: String?,
)

data class StopTime(
    val tripId: String,
    val seq: Int,
    val stopId: String,
    val arrivalSec: Int,
    val departureSec: Int,
)

data class ShapePoint(val lat: Double, val lon: Double)

/** Aggregated live state of a single running vehicle. */
data class Vehicle(
    val tripId: String,
    val routeId: String,
    val headsign: String,
    val directionId: Int,
    val lat: Double?,
    val lon: Double?,
    val bearing: Float?,
    val progress: Float,
    val nextStopId: String?,
    val nextStopName: String?,
    val nextArrivalEpoch: Long?,
    val delaySec: Int,
    val realtime: Boolean,
    val updatedAt: Long,
)

data class TrafficAlert(
    val id: String,
    val header: String,
    val description: String,
    val severity: String,
    val cause: String,
    val effect: String,
    val lines: List<String>,
    val url: String?,
    val validFrom: Long?,
    val validUntil: Long?,
)