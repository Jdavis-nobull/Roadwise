package com.roadwise.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import com.roadwise.app.core.BBox
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Place
import com.roadwise.app.core.Report
import com.roadwise.app.core.SpeedCamera
import com.roadwise.app.reports.ReportType
import com.roadwise.app.routing.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Calendar
import kotlin.math.*

object MapStyles {
    const val DAY = "https://tiles.openfreemap.org/styles/liberty"
    const val NIGHT = "https://tiles.openfreemap.org/styles/dark"

    /** True if the sun is down at the given place right now (NOAA approximation). */
    fun isNight(lat: Double, lon: Double, cal: Calendar = Calendar.getInstance()): Boolean {
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val gamma = 2 * PI / 365 * (day - 1)
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val latR = Math.toRadians(lat)
        val cosHa = (cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl))) - tan(latR) * tan(decl)
        if (cosHa > 1) return true
        if (cosHa < -1) return false
        val ha = Math.toDegrees(acos(cosHa))
        val sunriseUtcMin = 720 - 4 * (lon + ha) - eqTime
        val sunsetUtcMin = 720 - 4 * (lon - ha) - eqTime
        val utcMin = (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) - (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 60_000
        val now = (((utcMin % 1440) + 1440) % 1440).toDouble()
        val rise = ((sunriseUtcMin % 1440) + 1440) % 1440
        val set = ((sunsetUtcMin % 1440) + 1440) % 1440
        return if (rise < set) now < rise || now > set else now in set..rise
    }
}

/** Owns the MapLibre map: styles, route/report/camera layers, the location puck and camera moves. */
class MapController(private val ctx: Context, val map: MapLibreMap) {
    var onMapClick: (LatLon, Place?, String?) -> Unit = { _, _, _ -> }
    var onLongClick: (LatLon) -> Unit = {}
    var onUserMoved: () -> Unit = {}
    var onIdle: (BBox, Double) -> Unit = { _, _ -> }

    private var styleUrl = ""
    private var style: Style? = null
    private var poiLayers: Array<String> = emptyArray()
    private var tracking = false
    private var navMode = false

    // last data so we can re-apply after a style switch
    private var routes: List<Route> = emptyList()
    private var selected = 0
    private var reports: List<Report> = emptyList()
    private var cameras: List<SpeedCamera> = emptyList()
    private var pin: Place? = null
    private var results: List<Place> = emptyList()
    private var traveledTo: LatLon? = null
    private var lastLocation: Location? = null

    init {
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.setCompassMargins(0, dp(150), dp(12), 0)
        map.uiSettings.isAttributionEnabled = true
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.setAttributionMargins(dp(8), 0, 0, dp(4))
        map.addOnMapClickListener { ll ->
            val pt = map.projection.toScreenLocation(ll)
            val rect = RectF(pt.x - dp(14), pt.y - dp(14), pt.x + dp(14), pt.y + dp(14))
            val rep = map.queryRenderedFeatures(rect, L_REPORTS).firstOrNull()?.getStringProperty("id")
            if (rep != null) { onMapClick(LatLon(ll.latitude, ll.longitude), null, rep); return@addOnMapClickListener true }
            val res = map.queryRenderedFeatures(rect, L_RESULTS).firstOrNull()
            if (res != null) {
                val p = res.toPlace()
                if (p != null) { onMapClick(p.latLon, p, null); return@addOnMapClickListener true }
            }
            val poi = if (poiLayers.isNotEmpty()) map.queryRenderedFeatures(rect, *poiLayers).firstOrNull { it.hasNonNullValueForProperty("name") } else null
            val place = poi?.let { f ->
                val g = f.geometry() as? Point ?: return@let null
                val cls = listOfNotNull(f.getStringProp("class"), f.getStringProp("subclass")).distinct().joinToString(" ").replace('_', ' ')
                Place(f.getStringProperty("name"), "", g.latitude(), g.longitude(), cls, "map")
            }
            onMapClick(LatLon(ll.latitude, ll.longitude), place, null)
            true
        }
        map.addOnMapLongClickListener { ll -> onLongClick(LatLon(ll.latitude, ll.longitude)); true }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                tracking = false
                onUserMoved()
            }
        }
        map.addOnCameraIdleListener { onIdle(visibleBounds(), map.cameraPosition.zoom) }
    }

    private fun Feature.getStringProp(k: String) = if (hasNonNullValueForProperty(k)) getStringProperty(k) else null

    private fun Feature.toPlace(): Place? {
        val g = geometry() as? Point ?: return null
        return Place(getStringProp("name") ?: return null, getStringProp("address") ?: "", g.latitude(), g.longitude(), getStringProp("category") ?: "", "search")
    }

    private var satellite = false

    fun setStyle(url: String) = setMapMode(url, satellite)

    /**
     * Map look: a vector style (day/night) and optionally satellite photos under its roads/labels.
     * Satellite uses the night style's light-on-dark labels, which read best over photos.
     */
    fun setMapMode(url: String, sat: Boolean) {
        val effective = if (sat) MapStyles.NIGHT else url
        if (effective == styleUrl && sat == satellite) return
        styleUrl = effective
        satellite = sat
        style = null
        map.setStyle(Style.Builder().fromUri(effective)) { st -> setup(st) }
    }

    /** Hybrid view: satellite imagery at the bottom, map fills hidden, roads faint, labels kept. */
    private fun applySatellite(st: Style) {
        if (st.getLayer(L_SAT) != null) return
        st.addSource(org.maplibre.android.style.sources.RasterSource(S_SAT,
            org.maplibre.android.style.sources.TileSet("2.2.0", SATELLITE_TILES).apply {
                maxZoom = 19f
                attribution = "Imagery © Esri, Maxar, Earthstar Geographics, and the GIS User Community"
            }, 256))
        for (l in st.layers) {
            if (l.id.startsWith("rw-")) continue
            when (l) {
                is org.maplibre.android.style.layers.FillLayer,
                is org.maplibre.android.style.layers.FillExtrusionLayer,
                is org.maplibre.android.style.layers.HillshadeLayer,
                is org.maplibre.android.style.layers.RasterLayer,
                is org.maplibre.android.style.layers.BackgroundLayer ->
                    l.setProperties(PropertyFactory.visibility(Property.NONE))
                is LineLayer -> when (l.sourceLayer) {
                    "transportation" -> l.setProperties(PropertyFactory.lineOpacity(0.35f))
                    "boundary" -> {}
                    else -> l.setProperties(PropertyFactory.visibility(Property.NONE))
                }
                // Crisp white labels with a dark outline read best over photos.
                is SymbolLayer -> l.setProperties(
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textHaloColor("#CC000000"),
                    PropertyFactory.textHaloWidth(1.6f))
                else -> {}
            }
        }
        val bottom = st.layers.firstOrNull()?.id
        val layer = org.maplibre.android.style.layers.RasterLayer(L_SAT, S_SAT)
        if (bottom != null) st.addLayerBelow(layer, bottom) else st.addLayer(layer)
    }

    /** Safety net called by the MapView whenever any style finishes loading. */
    fun onStyleFinishedLoading() { map.getStyle { st -> setup(st) } }

    /** Adds our icons, sources and layers to a freshly loaded style (idempotent). */
    private fun setup(st: Style) {
        if (style === st && st.getSource(S_ROUTE) != null) return
        style = st
        if (st.getSource(S_ROUTE) == null) {
            poiLayers = st.layers.filterIsInstance<SymbolLayer>().filter { it.sourceLayer in setOf("poi", "aerodrome_label", "mountain_peak") }.map { it.id }.toTypedArray()
            addImages(st)
            addLayers(st)
        }
        if (satellite) applySatellite(st)
        lastDrawn = ""
        renderAll()
    }

    private fun firstSymbolLayer(st: Style): String? = st.layers.firstOrNull { it is SymbolLayer }?.id

    private fun addLayers(st: Style) {
        st.addSource(GeoJsonSource(S_ALT))
        st.addSource(GeoJsonSource(S_ROUTE))
        st.addSource(GeoJsonSource(S_TRAVELED))
        st.addSource(GeoJsonSource(S_REPORTS))
        st.addSource(GeoJsonSource(S_CAMERAS))
        st.addSource(GeoJsonSource(S_PIN))
        st.addSource(GeoJsonSource(S_RESULTS))
        // Draw routes just above the last road/line layer so roads never cover them, but below labels.
        var anchor: String? = st.layers.lastOrNull { it is LineLayer && !it.id.startsWith("rw-") }?.id
        val width = Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
            Expression.stop(8, 3f), Expression.stop(14, 7f), Expression.stop(18, 16f))
        val casing = Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
            Expression.stop(8, 5f), Expression.stop(14, 10f), Expression.stop(18, 21f))
        fun add(layer: org.maplibre.android.style.layers.Layer) {
            val a = anchor
            if (a != null) st.addLayerAbove(layer, a) else st.addLayer(layer)
            anchor = layer.id
        }
        add(LineLayer(L_ALT_CASE, S_ALT).withProperties(
            PropertyFactory.lineColor("#5F6F86"), PropertyFactory.lineWidth(casing),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        add(LineLayer(L_ALT, S_ALT).withProperties(
            PropertyFactory.lineColor("#A9B8CF"), PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        add(LineLayer(L_ROUTE_CASE, S_ROUTE).withProperties(
            PropertyFactory.lineColor("#0B47A1"), PropertyFactory.lineWidth(casing),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        add(LineLayer(L_ROUTE, S_ROUTE).withProperties(
            PropertyFactory.lineColor("#1A73E8"), PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        add(LineLayer(L_TRAVELED, S_TRAVELED).withProperties(
            PropertyFactory.lineColor("#9AA5B1"), PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        st.addLayer(CircleLayer(L_RESULTS_DOT, S_RESULTS).withProperties(
            PropertyFactory.circleRadius(7f), PropertyFactory.circleColor("#E53935"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)))
        st.addLayer(SymbolLayer(L_RESULTS, S_RESULTS).withProperties(
            PropertyFactory.textField(Expression.get("name")), PropertyFactory.textSize(12f),
            PropertyFactory.textOffset(arrayOf(0f, 1.3f)), PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textColor("#B71C1C"), PropertyFactory.textHaloColor("#FFFFFF"), PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")), PropertyFactory.textOptional(true)))
        st.addLayer(SymbolLayer(L_CAMERAS, S_CAMERAS).withProperties(
            PropertyFactory.iconImage("cam"), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconSize(0.85f)))
        st.addLayer(SymbolLayer(L_REPORTS, S_REPORTS).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)))
        st.addLayer(SymbolLayer(L_PIN, S_PIN).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)))
        // "You are here": accuracy halo + arrow that turns with the phone / direction of travel.
        st.addSource(GeoJsonSource(S_ME))
        st.addLayer(CircleLayer(L_ME_ACC, S_ME).withProperties(
            PropertyFactory.circleRadius(Expression.get("acc")), PropertyFactory.circleColor("#1A73E8"),
            PropertyFactory.circleOpacity(0.13f), PropertyFactory.circleStrokeColor("#1A73E8"),
            PropertyFactory.circleStrokeOpacity(0.3f), PropertyFactory.circleStrokeWidth(1f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))
        st.addLayer(SymbolLayer(L_ME_BEAM, S_ME).withProperties(
            PropertyFactory.iconImage(Expression.get("beam")),
            PropertyFactory.iconOpacity(Expression.get("beamOpacity")),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        st.addLayer(SymbolLayer(L_ME, S_ME).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
    }

    private fun addImages(st: Style) {
        st.addImage("me-arrow", meArrowBitmap())
        st.addImage("me-dot", meDotBitmap())
        st.addImage("me-nav", navArrowBitmap())
        st.addImage("beam-narrow", beamBitmap(30f))
        st.addImage("beam-mid", beamBitmap(60f))
        st.addImage("beam-wide", beamBitmap(100f))
        for (t in ReportType.entries) st.addImage("rep-${t.key}", markerBitmap(t.emoji, t.color.toInt()))
        st.addImage("cam", circleBitmap("📷", 0xFF37474F.toInt(), 30))
        st.addImage("pin", pinBitmap(0xFFE53935.toInt()))
        st.addImage("dest", pinBitmap(0xFF1A73E8.toInt(), "🏁"))
    }

    // ---------- data ----------
    fun setRoutes(list: List<Route>, sel: Int) { routes = list; selected = sel; renderRoutes() }
    fun setReports(list: List<Report>) { reports = list; renderReports() }
    fun setCameras(list: List<SpeedCamera>) { cameras = list; renderCameras() }
    fun setPin(p: Place?) { pin = p; renderPin() }
    fun setResults(list: List<Place>) { results = list; renderResults() }
    fun setTraveled(upTo: LatLon?) { traveledTo = upTo; renderTraveled() }

    private fun renderAll() { renderRoutes(); renderReports(); renderCameras(); renderPin(); renderResults() }

    private fun src(id: String): GeoJsonSource? {
        return style?.takeIf { it.isFullyLoaded }?.getSourceAs<GeoJsonSource>(id)
    }

    private fun line(points: List<LatLon>) = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })

    private fun renderRoutes() {
        val main = routes.getOrNull(selected)
        val alts = routes.filterIndexed { i, _ -> i != selected }
        src(S_ALT)?.setGeoJson(FeatureCollection.fromFeatures(alts.map { Feature.fromGeometry(line(it.points)) }))
        src(S_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(main?.let {
            Feature.fromGeometry(line(it.points))
        })))
        (style?.getLayer(L_ROUTE) as? LineLayer)?.setProperties(
            PropertyFactory.lineColor(if (main?.source == "offline") "#9575CD" else "#4C8DF6"))
        renderTraveled()
    }

    private fun renderTraveled() {
        val r = routes.getOrNull(selected); val upTo = traveledTo
        if (r == null || upTo == null) { src(S_TRAVELED)?.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        var bi = 0; var bd = Double.MAX_VALUE
        for (i in r.points.indices) { val d = com.roadwise.app.core.Geo.dist(r.points[i], upTo); if (d < bd) { bd = d; bi = i } }
        val pts = r.points.subList(0, bi + 1) + upTo
        src(S_TRAVELED)?.setGeoJson(if (pts.size >= 2) FeatureCollection.fromFeature(Feature.fromGeometry(line(pts))) else FeatureCollection.fromFeatures(emptyList()))
    }

    private fun renderReports() {
        src(S_REPORTS)?.setGeoJson(FeatureCollection.fromFeatures(reports.map { r ->
            Feature.fromGeometry(Point.fromLngLat(r.lon, r.lat)).apply {
                addStringProperty("id", r.id); addStringProperty("icon", "rep-${ReportType.of(r.type).key}")
            }
        }))
    }

    private fun renderCameras() {
        src(S_CAMERAS)?.setGeoJson(FeatureCollection.fromFeatures(cameras.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }))
    }

    private fun renderPin() {
        val p = pin
        src(S_PIN)?.setGeoJson(if (p == null) FeatureCollection.fromFeatures(emptyList()) else
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("icon", if (routes.isNotEmpty()) "dest" else "pin")
            }))
    }

    private fun renderResults() {
        src(S_RESULTS)?.setGeoJson(FeatureCollection.fromFeatures(results.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("name", p.name); addStringProperty("address", p.address); addStringProperty("category", p.category)
            }
        }))
    }

    // ---------- location + camera (animated every frame) ----------
    //
    // Your position is drawn by our own layer and moved on every screen frame, so it glides
    // smoothly between GPS fixes instead of jumping once a second. While navigating it is
    // predicted along the route at your current speed (like Waze / Google Maps).

    private var fix: Location? = null
    private var compass: Float? = null
    private var compassError = 45f
    private var match: com.roadwise.app.nav.RoadMatch? = null
    private var navFixNanos = 0L
    private var dispLat = Double.NaN
    private var dispLon = Double.NaN
    private var dispBearing = 0.0
    private var dispAcc = 0.0
    private var lastFrame = 0L
    private var lastDrawn = ""
    private var navRoute: Route? = null
    private var navProgress = 0.0
    private var navSpeed = 0.0
    private var navOnRoute = false
    private var dispProgress = Double.NaN
    private var navZoom = 16.5
    private var navTilt = 55.0
    private var running = true

    private val frame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            runCatching { tick() }
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init { android.view.Choreographer.getInstance().postFrameCallback(frame) }

    fun dispose() { running = false }

    /** Part of the screen not covered by UI (Android Auto cards); null on the phone. */
    var viewport: android.graphics.Rect? = null

    /** Map padding while navigating: car low in the visible area. */
    private fun navPadding(): DoubleArray {
        val v = viewport ?: return doubleArrayOf(0.0, map.height * 0.42, 0.0, 0.0)
        return doubleArrayOf(v.left.toDouble(), v.top + v.height() * 0.45, (map.width - v.right).toDouble(), (map.height - v.bottom).toDouble())
    }

    fun stopTracking() { tracking = false }

    fun zoomBy(delta: Double) { map.animateCamera(CameraUpdateFactory.zoomBy(delta), 300) }

    /** Kept for callers; the position layer is set up with the style. */
    fun enableLocation() {}

    fun updateLocation(loc: Location) {
        fix = loc
        lastLocation = loc
        if (dispLat.isNaN()) { dispLat = loc.latitude; dispLon = loc.longitude; dispAcc = loc.accuracy.toDouble() }
    }

    /** Phone heading in degrees (true north) or null if no compass, with its estimated error. */
    fun setCompass(deg: Float?, errorDeg: Float = compassError) { compass = deg; compassError = errorDeg }

    /** Where the latest fix sits on the road network (null when not driving / no road nearby). */
    fun setRoadMatch(m: com.roadwise.app.nav.RoadMatch?) { match = m }

    /** Navigation progress, so the arrow can glide along the road between fixes. */
    fun setNavProgress(route: Route?, progressM: Double, speedMps: Double, onRoute: Boolean) {
        if (route !== navRoute) dispProgress = Double.NaN
        navRoute = route; navProgress = progressM; navSpeed = speedMps; navOnRoute = onRoute
        // Time the fix was MEASURED (not when it arrived), so prediction makes up for GPS delay.
        navFixNanos = fix?.elapsedRealtimeNanos ?: android.os.SystemClock.elapsedRealtimeNanos()
    }

    /** Seconds since a fix was measured, capped (GPS fixes usually arrive ~0.3-1 s late). */
    private fun ageSec(nanos: Long, cap: Double) =
        ((android.os.SystemClock.elapsedRealtimeNanos() - nanos) / 1e9).coerceIn(0.0, cap)

    private fun tick() {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016 else ((now - lastFrame) / 1000.0).coerceIn(0.0, 0.25)
        if (now - lastFrame < 30) return // ~30 fps is plenty and saves battery
        lastFrame = now
        val f = fix ?: return
        val speed = if (f.hasSpeed()) f.speed.toDouble() else 0.0
        // Trust the GPS direction of travel only when it's actually reliable.
        val courseOk = f.hasBearing() && speed > 1.0 &&
            (!f.hasBearingAccuracy() || f.bearingAccuracyDegrees < 35f)
        val moving = speed > 2.5 && courseOk

        var targetLat: Double; var targetLon: Double; var course: Double? = null
        val r = navRoute
        if (navMode && r != null && navOnRoute && r.points.size > 1) {
            // Predict along the route from when the fix was measured (max 2.5 s).
            val ahead = navSpeed * ageSec(navFixNanos, 2.5)
            val target = (navProgress + ahead).coerceAtMost(r.distanceM)
            dispProgress = if (dispProgress.isNaN() || Math.abs(target - dispProgress) > 80) target
                else dispProgress + (target - dispProgress) * (dt * 5).coerceAtMost(1.0)
            val (p, b) = pointOnRoute(r, dispProgress)
            targetLat = p.lat; targetLon = p.lon; course = b
            dispLat = targetLat; dispLon = targetLon
        } else {
            // Start from the fix, or from the road it sits on while driving.
            val m = match?.takeIf { it.fixNanos == f.elapsedRealtimeNanos && moving }
            targetLat = m?.point?.lat ?: f.latitude
            targetLon = m?.point?.lon ?: f.longitude
            if (moving) {
                course = m?.bearing ?: f.bearing.toDouble()
                // Dead-reckon forward by the fix's age (covers GPS delay and time between fixes).
                val d = speed * ageSec(f.elapsedRealtimeNanos, 2.0)
                val br = Math.toRadians(course)
                targetLat += d * cos(br) / 111_320.0
                targetLon += d * sin(br) / (111_320.0 * cos(Math.toRadians(targetLat)))
            }
            val jump = com.roadwise.app.core.Geo.dist(dispLat, dispLon, targetLat, targetLon)
            when {
                dispLat.isNaN() || jump > 300 -> { dispLat = targetLat; dispLon = targetLon }
                // Standing still: ignore drift that's inside the GPS accuracy, so the dot doesn't wander.
                speed < 0.7 && jump < maxOf(2.0, f.accuracy * 0.4) -> {}
                else -> {
                    val k = (dt * if (moving) 9 else 5).coerceAtMost(1.0)
                    dispLat += (targetLat - dispLat) * k; dispLon += (targetLon - dispLon) * k
                }
            }
        }

        // Heading: GPS/road direction when moving, the compass when slow or still,
        // and a blend of the two while walking speed picks up.
        val c = compass?.toDouble()
        val gps = if (courseOk) (match?.takeIf { it.fixNanos == f.elapsedRealtimeNanos }?.bearing ?: f.bearing.toDouble()) else null
        val usingCompass: Boolean
        val wanted: Double? = when {
            course != null -> { usingCompass = false; course }
            c != null && gps != null && speed > 1.0 -> {
                usingCompass = speed < 2.0
                val w = ((speed - 1.0) / 1.5).coerceIn(0.0, 1.0) // 0 = compass, 1 = GPS
                var d = gps - c; while (d > 180) d -= 360.0; while (d < -180) d += 360.0
                (c + d * w + 360) % 360
            }
            c != null -> { usingCompass = true; c }
            gps != null -> { usingCompass = false; gps }
            else -> { usingCompass = false; null }
        }
        if (wanted != null) {
            var d = wanted - dispBearing
            while (d > 180) d -= 360.0
            while (d < -180) d += 360.0
            dispBearing = (dispBearing + d * (dt * 12).coerceAtMost(1.0) + 360) % 360
        }
        dispAcc += (f.accuracy - dispAcc) * (dt * 3).coerceAtMost(1.0)

        val icon = when {
            navMode -> "me-nav"
            wanted != null -> "me-arrow"
            else -> "me-dot"
        }
        // Heading-accuracy beam (like a flashlight): narrow = confident, wide = unsure.
        val beam = when {
            compassError <= 20 -> "beam-narrow"
            compassError <= 40 -> "beam-mid"
            else -> "beam-wide"
        }
        val beamOpacity = if (!navMode && usingCompass && wanted != null) 1.0 else 0.0
        val zoom = map.cameraPosition.zoom
        val metersPerPx = 40_075_016.0 * cos(Math.toRadians(dispLat)) / (512.0 * 2.0.pow(zoom))
        val accPx = if (navMode) 0.0 else (dispAcc / metersPerPx).coerceIn(0.0, 400.0)
        val key = "%.6f,%.6f,%.0f,%s,%.0f,%s,%.0f".format(dispLat, dispLon, dispBearing, icon, accPx, beam, beamOpacity)
        if (key != lastDrawn) {
            lastDrawn = key
            src(S_ME)?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(dispLon, dispLat)).apply {
                addNumberProperty("bearing", dispBearing)
                addStringProperty("icon", icon)
                addNumberProperty("acc", accPx)
                addStringProperty("beam", beam)
                addNumberProperty("beamOpacity", beamOpacity)
            })
        }

        if (!tracking) return
        if (navMode) {
            val z = when { navSpeed > 27 -> 15.3; navSpeed > 18 -> 15.9; navSpeed > 9 -> 16.4; else -> 16.9 }
            navZoom += (z - navZoom) * (dt * 0.8).coerceAtMost(1.0)
            map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                .target(LatLng(dispLat, dispLon)).zoom(navZoom).tilt(navTilt).bearing(dispBearing)
                .padding(navPadding()).build()))
        } else {
            val c = map.cameraPosition.target
            if (c == null || com.roadwise.app.core.Geo.dist(c.latitude, c.longitude, dispLat, dispLon) > 0.5) {
                map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(dispLat, dispLon)))
            }
        }
    }

    private fun pointOnRoute(r: Route, d: Double): Pair<LatLon, Double> {
        val cum = r.cumDist
        var lo = 0; var hi = cum.size - 1
        while (lo < hi - 1) { val mid = (lo + hi) / 2; if (cum[mid] <= d) lo = mid else hi = mid }
        val a = r.points[lo]; val b = r.points[hi]
        val seg = (cum[hi] - cum[lo]).coerceAtLeast(0.01)
        val t = ((d - cum[lo]) / seg).coerceIn(0.0, 1.0)
        return com.roadwise.app.core.Geo.interpolate(a, b, t) to com.roadwise.app.core.Geo.bearing(a, b)
    }

    fun setNavMode(on: Boolean, tilt: Boolean) {
        navMode = on
        tracking = on
        navTilt = if (tilt) 55.0 else 0.0
        navZoom = 16.5
        dispProgress = Double.NaN
        if (on) {
            if (!dispLat.isNaN()) map.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                .target(LatLng(dispLat, dispLon)).zoom(navZoom).tilt(navTilt).bearing(dispBearing)
                .padding(navPadding()).build()), 900)
        } else {
            navRoute = null
            map.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition)
                .tilt(0.0).bearing(0.0).padding(0.0, 0.0, 0.0, 0.0).build()), 500)
        }
    }

    fun recenter(tilt: Boolean) {
        if (navMode) { setNavMode(true, tilt); return }
        val target = if (!dispLat.isNaN()) LatLng(dispLat, dispLon) else lastLocation?.let { LatLng(it.latitude, it.longitude) } ?: return
        map.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(target).zoom(max(map.cameraPosition.zoom, 15.0)).bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()), 700,
            object : MapLibreMap.CancelableCallback {
                override fun onCancel() {}
                override fun onFinish() { tracking = true }
            })
    }

    val isTracking get() = tracking

    private fun applyCameraMode() {}

    fun flyTo(p: LatLon, zoom: Double = 15.5) {
        tracking = false; applyCameraMode()
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), zoom), 800)
    }

    fun fit(points: List<LatLon>, bottomPadPx: Int) {
        if (points.isEmpty()) return
        tracking = false; applyCameraMode()
        if (points.size == 1) { flyTo(points[0]); return }
        val b = LatLngBounds.Builder()
        points.forEach { b.include(LatLng(it.lat, it.lon)) }
        val v = viewport
        if (v != null) map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), v.left + dp(24), v.top + dp(24), (map.width - v.right).toInt() + dp(24), (map.height - v.bottom).toInt() + dp(24)), 800)
        else map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(48), dp(190), dp(48), bottomPadPx + dp(24)), 800)
    }

    fun visibleBounds(): BBox {
        val r = map.projection.visibleRegion.latLngBounds
        return BBox(r.latitudeSouth, r.longitudeWest, r.latitudeNorth, r.longitudeEast)
    }

    fun center(): LatLon = map.cameraPosition.target?.let { LatLon(it.latitude, it.longitude) } ?: LatLon(0.0, 0.0)

    fun moveTo(p: LatLon, zoom: Double) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), zoom))
    }

    // ---------- bitmaps ----------
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun markerBitmap(emoji: String, color: Int): Bitmap {
        val w = dp(40); val h = dp(48)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = w / 2f - dp(2)
        val path = Path().apply {
            moveTo(w / 2f, h.toFloat() - dp(1))
            lineTo(w / 2f - r * 0.55f, w / 2f + r * 0.7f)
            lineTo(w / 2f + r * 0.55f, w / 2f + r * 0.7f)
            close()
        }
        p.color = color; c.drawPath(path, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(w / 2f, w / 2f, r, p)
        p.color = color; c.drawCircle(w / 2f, w / 2f, r - dp(3), p)
        drawEmoji(c, emoji, w / 2f, w / 2f, r * 1.1f)
        return bmp
    }

    /** Blue dot with a small arrow on top pointing the way the phone faces (rotated by the layer). */
    private fun meArrowBitmap(): Bitmap {
        val s = dp(44)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = s / 2f; val cy = s / 2f
        // arrow head
        p.color = 0xFFFFFFFF.toInt()
        c.drawPath(Path().apply {
            moveTo(cx, dp(1).toFloat()); lineTo(cx - dp(9), cy - dp(5)); lineTo(cx + dp(9), cy - dp(5)); close()
        }, p)
        p.color = 0xFF1A73E8.toInt()
        c.drawPath(Path().apply {
            moveTo(cx, dp(4).toFloat()); lineTo(cx - dp(6.5f), cy - dp(6)); lineTo(cx + dp(6.5f), cy - dp(6)); close()
        }, p)
        drawDot(c, cx, cy, p)
        return bmp
    }

    /** Fading blue wedge pointing up from the centre, [spread] degrees wide. */
    private fun beamBitmap(spread: Float): Bitmap {
        val s = dp(120)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = s / 2f; val cy = s / 2f; val r = s / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(cx, cy, r,
                intArrayOf(0x991A73E8.toInt(), 0x441A73E8, 0x001A73E8), floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
        }
        // Canvas angles: 0 = east, -90 = up.
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f - spread / 2, spread, true, paint)
        return bmp
    }

    private fun meDotBitmap(): Bitmap {
        val s = dp(44)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        drawDot(Canvas(bmp), s / 2f, s / 2f, Paint(Paint.ANTI_ALIAS_FLAG))
        return bmp
    }

    private fun drawDot(c: Canvas, cx: Float, cy: Float, p: Paint) {
        p.color = 0x55000000; c.drawCircle(cx, cy + dp(1), dp(10).toFloat(), p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, dp(10).toFloat(), p)
        p.color = 0xFF1A73E8.toInt(); c.drawCircle(cx, cy, dp(7).toFloat(), p)
    }

    /** Driving chevron used while navigating. */
    private fun navArrowBitmap(): Bitmap {
        val s = dp(56)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = s / 2f
        fun chevron(inset: Float) = Path().apply {
            moveTo(cx, dp(4) + inset)
            lineTo(s - dp(8) - inset, s - dp(8) - inset * 0.6f)
            lineTo(cx, s - dp(18) - inset * 0.2f)
            lineTo(dp(8) + inset, s - dp(8) - inset * 0.6f)
            close()
        }
        p.color = 0x66000000; c.save(); c.translate(0f, dp(2).toFloat()); c.drawPath(chevron(0f), p); c.restore()
        p.color = 0xFFFFFFFF.toInt(); c.drawPath(chevron(0f), p)
        p.color = 0xFF1A73E8.toInt(); c.drawPath(chevron(dp(3).toFloat()), p)
        return bmp
    }

    private fun dp(v: Float) = v * ctx.resources.displayMetrics.density

    private fun circleBitmap(emoji: String, color: Int, sizeDp: Int): Bitmap {
        val s = dp(sizeDp)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = color; c.drawCircle(s / 2f, s / 2f, s / 2f - dp(2), p)
        drawEmoji(c, emoji, s / 2f, s / 2f, s * 0.5f)
        return bmp
    }

    private fun pinBitmap(color: Int, emoji: String? = null): Bitmap {
        val w = dp(36); val h = dp(50)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val r = w / 2f - dp(1)
        val path = Path().apply {
            addCircle(w / 2f, r + dp(1), r, Path.Direction.CW)
            moveTo(w / 2f - r * 0.8f, r + r * 0.6f)
            lineTo(w / 2f, h.toFloat())
            lineTo(w / 2f + r * 0.8f, r + r * 0.6f)
            close()
        }
        c.drawPath(path, p)
        if (emoji != null) drawEmoji(c, emoji, w / 2f, r + dp(1), r * 1.1f)
        else { p.color = 0xFFFFFFFF.toInt(); c.drawCircle(w / 2f, r + dp(1), r * 0.38f, p) }
        return bmp
    }

    private fun drawEmoji(c: Canvas, s: String, cx: Float, cy: Float, size: Float) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
        val fm = tp.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, tp)
    }

    companion object {
        const val S_ALT = "rw-alt"; const val S_ROUTE = "rw-route"; const val S_TRAVELED = "rw-traveled"
        const val S_REPORTS = "rw-reports"; const val S_CAMERAS = "rw-cameras"; const val S_PIN = "rw-pin"; const val S_RESULTS = "rw-results"
        const val L_ALT = "rw-alt"; const val L_ALT_CASE = "rw-alt-case"; const val L_ROUTE = "rw-route"; const val L_ROUTE_CASE = "rw-route-case"
        const val L_TRAVELED = "rw-traveled"; const val L_REPORTS = "rw-reports"; const val L_CAMERAS = "rw-cameras"; const val L_PIN = "rw-pin"
        const val L_RESULTS = "rw-results"; const val L_RESULTS_DOT = "rw-results-dot"
        const val S_SAT = "rw-sat"; const val L_SAT = "rw-sat"
        const val SATELLITE_TILES = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        const val S_ME = "rw-me"; const val L_ME = "rw-me"; const val L_ME_ACC = "rw-me-acc"; const val L_ME_BEAM = "rw-me-beam"
    }
}
