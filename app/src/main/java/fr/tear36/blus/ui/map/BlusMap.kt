package fr.tear36.blus.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import fr.tear36.blus.data.Route
import fr.tear36.blus.data.ShapePoint
import fr.tear36.blus.data.Stop
import fr.tear36.blus.data.Vehicle
import fr.tear36.blus.ui.LatLon
import fr.tear36.blus.ui.parseGtfsColor
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

/** Nantes centre — used before the first location fix. */
val NANTES_CENTER = GeoPoint(47.2184, -1.5536)

/** Stop names are only worth drawing once the stops stop overlapping. */
private const val LABEL_MIN_ZOOM = 15.0

data class MapData(
    val vehicles: List<Vehicle>,
    val stops: List<Stop>,
    val routes: Map<String, Route>,
    val shapes: Map<String, List<ShapePoint>>,
    val visibleRouteIds: Set<String>,
    val selectedStopId: String?,
    val center: LatLon?,
    val onStopClick: (Stop) -> Unit,
    val onVehicleClick: (Vehicle) -> Unit,
)

/**
 * OpenStreetMap map showing the line shapes, nearby stops and live vehicles.
 *
 * Stops and shapes are drawn in canvas passes (a Marker per stop would choke the view);
 * vehicles get real [Marker]s because there are few and they need hit-testing.
 */
@Composable
fun BlusMap(
    data: MapData,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.0)
            controller.setCenter(NANTES_CENTER)
            minZoomLevel = 11.0
            maxZoomLevel = 19.0
            isTilesScaledToDpi = true
        }
    }

    val lineManager = remember { LineOverlay(mapView) }
    val stopOverlay = remember { StopOverlay(mapView, data.onStopClick) }
    val markerManager = remember { VehicleMarkerManager(mapView, data.onVehicleClick) }

    DisposableEffect(mapView) {
        mapView.overlays.add(stopOverlay)
        mapView.onResume()
        onDispose {
            mapView.overlays.remove(stopOverlay)
            lineManager.clear()
            markerManager.clear()
            mapView.onPause()
        }
    }

    val lifecycle = remember(lifecycleOwner) { lifecycleOwner.lifecycle }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(data.vehicles, data.routes) {
        markerManager.sync(data.vehicles, data.routes)
    }
    LaunchedEffect(data.shapes, data.visibleRouteIds, data.routes) {
        lineManager.sync(data.routes, data.shapes, data.visibleRouteIds)
    }
    LaunchedEffect(data.stops, data.selectedStopId) {
        stopOverlay.update(data.stops, data.selectedStopId)
        mapView.invalidate()
    }
    LaunchedEffect(data.center) {
        data.center?.let { mapView.controller.animateTo(GeoPoint(it.lat, it.lon)) }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { it.invalidate() },
        )

        IconButton(
            onClick = onRecenter,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .padding(16.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    MaterialTheme.shapes.small,
                ),
        ) {
            Icon(
                Icons.Default.MyLocation,
                contentDescription = "Recentrer",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Line shapes: one Polyline per visible route
// ---------------------------------------------------------------------------

private class LineOverlay(private val mapView: MapView) {

    private val polylines = HashMap<String, Polyline>()

    fun sync(
        routes: Map<String, Route>,
        shapes: Map<String, List<ShapePoint>>,
        visible: Set<String>,
    ) {
        val wanted = shapes.keys.filter { it in visible }
        var changed = false
        polylines.keys.filter { it !in wanted }.forEach { key ->
            mapView.overlays.remove(polylines.remove(key))
            changed = true
        }
        for (routeId in wanted) {
            if (polylines.containsKey(routeId)) continue
            val color = parseGtfsColor(routes[routeId]?.color, Color(0xFF8A94A6)).toArgb()
            val line = shapePolyline(shapes[routeId].orEmpty(), color, LINE_WIDTH)
            line.outlinePaint.alpha = 215
            // Insert at the bottom so stops and vehicles keep drawing on top.
            mapView.overlays.add(0, line)
            polylines[routeId] = line
            changed = true
        }
        if (changed) mapView.invalidate()
    }

    fun clear() {
        polylines.values.forEach { mapView.overlays.remove(it) }
        polylines.clear()
    }

    private companion object {
        /** Overlay units are already density scaled, so this is ~4.5 dp on screen. */
        const val LINE_WIDTH = 4.5f
    }
}

// ---------------------------------------------------------------------------
// Stops: single canvas pass
// ---------------------------------------------------------------------------

private class StopOverlay(
    private val mapView: MapView,
    private val onClick: (Stop) -> Unit,
) : Overlay() {

    private var stops: List<Stop> = emptyList()
    private var selectedId: String? = null

    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        textAlign = Paint.Align.LEFT
        textSize = LABEL_TEXT_SIZE
        color = AndroidColor.WHITE
    }
    private val labelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        textSize = LABEL_TEXT_SIZE
        color = AndroidColor.rgb(28, 28, 28)
    }
    private val placed = ArrayList<RectF>(128)

    fun update(stops: List<Stop>, selectedId: String?) {
        this.stops = stops
        this.selectedId = selectedId
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (stops.isEmpty()) return
        val p = Point()

        halo.color = AndroidColor.argb(210, 255, 255, 255)
        halo.strokeWidth = 1.5f
        for (s in stops) {
            projection.toPixels(GeoPoint(s.lat, s.lon), p)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 3.2f, halo)
        }

        dot.color = AndroidColor.argb(235, 0, 122, 69)
        for (s in stops) {
            if (s.id == selectedId) continue
            projection.toPixels(GeoPoint(s.lat, s.lon), p)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 2.4f, dot)
        }

        val selected = stops.firstOrNull { it.id == selectedId }
        if (selected != null) {
            projection.toPixels(GeoPoint(selected.lat, selected.lon), p)
            halo.color = AndroidColor.argb(240, 255, 255, 255)
            halo.strokeWidth = 2f
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 6f, halo)
            dot.color = AndroidColor.rgb(20, 20, 20)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 3.4f, dot)
        }

        if (mapView.zoomLevelDouble < LABEL_MIN_ZOOM) return
        placed.clear()
        for (s in stops) {
            projection.toPixels(GeoPoint(s.lat, s.lon), p)
            val x = p.x + 5f
            val y = p.y - 4f
            val width = labelFill.measureText(s.name)
            val box = RectF(x - 3f, y - labelFill.textSize, x + width + 3f, y + 4f)
            var clash = false
            for (other in placed) {
                if (RectF.intersects(other, box)) { clash = true; break }
            }
            if (clash) continue
            placed.add(box)
            canvas.drawText(s.name, x, y, labelHalo)
            canvas.drawText(s.name, x, y, labelFill)
        }
    }

    private companion object {
        /** Overlay units are density scaled, so this renders as 11 sp on screen. */
        const val LABEL_TEXT_SIZE = 11f
    }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        val tolerance = 48f
        var best: Stop? = null
        var bestDist = Float.MAX_VALUE
        for (s in stops) {
            val screen = Point()
            mapView.projection.toPixels(GeoPoint(s.lat, s.lon), screen)
            val dx = screen.x - e.x
            val dy = screen.y - e.y
            val d = dx * dx + dy * dy
            if (d < tolerance * tolerance && d < bestDist) {
                bestDist = d
                best = s
            }
        }
        return if (best != null) { onClick(best); true } else false
    }
}

// ---------------------------------------------------------------------------
// Vehicles: Marker per trip, added/removed as the feed changes
// ---------------------------------------------------------------------------

private class VehicleMarkerManager(
    private val mapView: MapView,
    private val onClick: (Vehicle) -> Unit,
) {
    private class Entry(val marker: Marker, val iconKey: String, var vehicle: Vehicle)

    private val markers = LinkedHashMap<String, Entry>()

    fun sync(vehicles: List<Vehicle>, routes: Map<String, Route>) {
        val positioned = vehicles.filter { it.lat != null && it.lon != null }
        val seen = HashSet<String>(positioned.size)
        var dirty = false

        for (v in positioned) {
            val tripId = v.tripId
            seen.add(tripId)
            val route = routes[v.routeId]
            val argb = parseGtfsColor(route?.color, Color.White).toArgb()
            val label = route?.shortName.orEmpty().take(4)
            val iconKey = "${argb.toUInt().toString(16)}|$label"

            val existing = markers[tripId]
            if (existing == null || existing.iconKey != iconKey) {
                existing?.let { mapView.overlays.remove(it.marker) }
                val marker = Marker(mapView).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Véhicule"
                    icon = vehicleDrawable(mapView.resources, argb, label)
                    setOnMarkerClickListener { _, _ ->
                        markers[tripId]?.vehicle?.let(onClick)
                        true
                    }
                }
                marker.position = GeoPoint(v.lat!!, v.lon!!)
                mapView.overlays.add(marker)
                markers[tripId] = Entry(marker, iconKey, v)
                dirty = true
                continue
            }

            existing.vehicle = v
            val target = GeoPoint(v.lat!!, v.lon!!)
            if (existing.marker.position.latitude != target.latitude ||
                existing.marker.position.longitude != target.longitude
            ) {
                existing.marker.position = target
                dirty = true
            }
        }

        markers.keys.filter { it !in seen }.forEach { key ->
            markers.remove(key)?.let { mapView.overlays.remove(it.marker) }
            dirty = true
        }

        if (dirty) mapView.invalidate()
    }

    fun clear() {
        markers.values.forEach { mapView.overlays.remove(it.marker) }
        markers.clear()
    }
}

private fun vehicleDrawable(resources: android.content.res.Resources, routeColor: Int, label: String): Drawable {
    val size = 104
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f

    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(80, 0, 0, 0) }
    c.drawCircle(r, r + 4f, r - 4f, shadow)

    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = AndroidColor.WHITE
    }
    c.drawCircle(r, r, r - 6f, border)

    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = routeColor
        isFakeBoldText = true
        textSize = if (label.length > 2) r * 0.72f else r * 0.95f
        textAlign = Paint.Align.CENTER
    }
    c.drawCircle(r, r, r - 8f, body)
    val baseline = r - (body.descent() + body.ascent()) / 2f
    c.drawText(label, r, baseline, body)

    return BitmapDrawable(resources, bmp)
}

/** Draws a GTFS shape as a polyline (used on the line detail sheet). */
fun shapePolyline(points: List<ShapePoint>, color: Int, width: Float): Polyline {
    val line = Polyline()
    if (points.size >= 2) line.setPoints(points.map { GeoPoint(it.lat, it.lon) })
    line.outlinePaint.color = color
    line.outlinePaint.strokeWidth = width
    line.outlinePaint.strokeCap = Paint.Cap.ROUND
    line.outlinePaint.strokeJoin = Paint.Join.ROUND
    return line
}
