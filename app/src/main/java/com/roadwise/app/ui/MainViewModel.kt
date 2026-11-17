package com.roadwise.app.ui

import android.app.Application
import android.content.Intent
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roadwise.app.RoadwiseApp
import com.roadwise.app.core.BBox
import com.roadwise.app.core.Geo
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Place
import com.roadwise.app.core.Report
import com.roadwise.app.core.SavedPlace
import com.roadwise.app.core.Settings
import com.roadwise.app.core.ThemeMode
import com.roadwise.app.nav.NavService
import com.roadwise.app.offline.AreaEstimate
import com.roadwise.app.reports.ReportType
import com.roadwise.app.routing.Route
import com.roadwise.app.routing.TravelMode
import com.roadwise.app.search.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Sheet {
    data object None : Sheet
    data class PlaceInfo(val place: Place) : Sheet
    data class Routes(
        val dest: Place, val routes: List<Route> = emptyList(), val selected: Int = 0,
        val loading: Boolean = true, val error: String? = null, val mode: TravelMode = TravelMode.DRIVE,
    ) : Sheet
    data class ReportInfo(val report: Report) : Sheet
}

enum class Screen { MAP, SEARCH, OFFLINE, SETTINGS }

data class UiState(
    val screen: Screen = Screen.MAP,
    val sheet: Sheet = Sheet.None,
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val category: String? = null,
    val reportPicker: Boolean = false,
    val shareDialog: Boolean = false,
    val layersSheet: Boolean = false,
    val selectingArea: Boolean = false,
    val areaBounds: BBox? = null,
    val areaEstimate: AreaEstimate? = null,
    val night: Boolean = false,
    val following: Boolean = true,
    val toast: String? = null,
    val saved: List<SavedPlace> = emptyList(),
    val recents: List<Place> = emptyList(),
    val zoom: Double = 4.0,
    val offlinePromptHidden: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val c = (app as RoadwiseApp).c
    private val _ui = MutableStateFlow(UiState(offlinePromptHidden = c.prefs.offlinePromptDismissed))
    val ui: StateFlow<UiState> = _ui
    val nav = c.nav.state
    val settings = c.prefs.settings
    val online = c.net.online
    val reports = c.reports.reports
    val reportStatus = c.reports.status
    val areas = c.offlineAreas.areas
    val simulating = c.simulator.running

    var map: MapController? = null
        private set
    private var searchJob: Job? = null
    private var lastRouteVersion = -1
    private var firstFix = true

    private val locListener: (Location) -> Unit = { loc ->
        map?.updateLocation(loc)
        if (firstFix) {
            firstFix = false
            if (_ui.value.sheet == Sheet.None && !nav.value.active) map?.recenter(false)
            updateTheme()
        }
    }

    init {
        c.location.addListener(locListener)
        refreshLists()
        viewModelScope.launch {
            reports.collect { list ->
                map?.setReports(list)
                c.nav.onReportsChanged()
                (_ui.value.sheet as? Sheet.ReportInfo)?.let { s -> if (list.none { it.id == s.report.id }) closeSheet() }
            }
        }
        viewModelScope.launch {
            var wasActive = false
            nav.collect { s ->
                val m = map ?: return@collect
                // Navigation can be started or ended here or from Android Auto; keep the phone map in step.
                if (s.active != wasActive) {
                    wasActive = s.active
                    if (s.active) {
                        _ui.update { it.copy(sheet = Sheet.None, following = true) }
                        m.setPin(s.destination)
                        m.setNavMode(true, settings.value.tilt3d)
                        lastRouteVersion = -1
                    } else {
                        m.setNavMode(false, false)
                        m.setRoutes(emptyList(), 0)
                        m.setTraveled(null)
                        m.setPin(null)
                        m.recenter(false)
                    }
                }
                if (s.active && s.route != null && s.routeVersion != lastRouteVersion) {
                    lastRouteVersion = s.routeVersion
                    m.setRoutes(listOf(s.route), 0)
                }
                if (s.active) m.setTraveled(s.snapped)
                m.setNavProgress(if (s.active && !s.arrived) s.route else null, s.progressM, s.speedMps, !s.offRoute && s.snapped != null)
            }
        }
        viewModelScope.launch { settings.collect { updateTheme() } }
        viewModelScope.launch { c.compass.heading.collect { map?.setCompass(it, c.compass.errorDeg.value) } }
        viewModelScope.launch { c.matcher.match.collect { map?.setRoadMatch(it) } }
        // Suggest a figure-8 once if the compass reports poor calibration for a while.
        viewModelScope.launch {
            var shown = false
            while (!shown) {
                delay(8_000)
                if (c.compass.needsCalibration && c.compass.heading.value != null && !nav.value.active) {
                    shown = true
                    toast("Compass is unsure - wave your phone in a figure-8 to improve the arrow")
                }
            }
        }
        viewModelScope.launch {
            while (true) { delay(5 * 60_000); updateTheme() }
        }
    }

    fun attachMap(m: MapController) {
        map = m
        m.onMapClick = ::onMapClick
        m.onLongClick = ::onLongClick
        m.onUserMoved = { _ui.update { it.copy(following = false) } }
        m.onIdle = ::onIdle
        val last = c.location.last ?: LatLon(c.prefs.lastLat, c.prefs.lastLon)
        m.moveTo(last, if (c.location.last != null || c.prefs.lastLat != 39.8283) 14.0 else 3.5)
        updateTheme(force = true)
        m.setReports(reports.value)
        c.location.location.value?.let { m.updateLocation(it) }
        val s = nav.value
        if (s.active && s.route != null) {
            lastRouteVersion = s.routeVersion
            m.setRoutes(listOf(s.route), 0); m.setPin(s.destination); m.setNavMode(true, settings.value.tilt3d)
        } else (ui.value.sheet as? Sheet.Routes)?.let { m.setRoutes(it.routes, it.selected); m.setPin(it.dest) }
    }

    fun detachMap() { map?.dispose(); map = null }

    fun onPermissionsResult() {
        c.location.acquire("ui", 1000)
        map?.enableLocation()
    }

    fun onResume() { if (c.location.hasPermission) c.location.acquire("ui", 1000); c.compass.start() }
    fun onPause() { c.location.release("ui"); c.compass.stop() }

    override fun onCleared() {
        c.location.removeListener(locListener)
        c.location.release("ui")
        super.onCleared()
    }

    private fun updateTheme(force: Boolean = false) {
        val s = settings.value
        val here = c.location.last ?: LatLon(c.prefs.lastLat, c.prefs.lastLon)
        val night = when (s.theme) {
            ThemeMode.DAY -> false
            ThemeMode.NIGHT -> true
            ThemeMode.AUTO -> MapStyles.isNight(here.lat, here.lon)
        }
        if (night != _ui.value.night || force) {
            _ui.update { it.copy(night = night) }
        }
        map?.setMapMode(if (night) MapStyles.NIGHT else MapStyles.DAY, s.satellite)
    }

    fun toast(msg: String) { _ui.update { it.copy(toast = msg) } }
    fun toastShown() { _ui.update { it.copy(toast = null) } }

    private fun refreshLists() = viewModelScope.launch(Dispatchers.IO) {
        val saved = c.db.savedPlaces(); val rec = c.db.recents()
        _ui.update { it.copy(saved = saved, recents = rec) }
    }

    val here: LatLon? get() = c.location.last

    // ---------------- navigation between screens ----------------
    fun openScreen(s: Screen) {
        _ui.update { it.copy(screen = s) }
        if (s == Screen.SEARCH) refreshLists()
        if (s == Screen.OFFLINE) c.offlineAreas.refresh()
    }

    fun back(): Boolean {
        val u = _ui.value
        when {
            u.screen != Screen.MAP -> _ui.update { it.copy(screen = Screen.MAP) }
            u.reportPicker -> _ui.update { it.copy(reportPicker = false) }
            u.shareDialog -> _ui.update { it.copy(shareDialog = false) }
            u.layersSheet -> _ui.update { it.copy(layersSheet = false) }
            u.selectingArea -> _ui.update { it.copy(selectingArea = false, screen = Screen.OFFLINE) }
            u.sheet != Sheet.None && !nav.value.active -> closeSheet()
            u.results.isNotEmpty() -> clearResults()
            else -> return false
        }
        return true
    }

    // ---------------- search ----------------
    fun setQuery(q: String) {
        _ui.update { it.copy(query = q, category = null) }
        searchJob?.cancel()
        if (q.trim().length < 2) { _ui.update { it.copy(results = emptyList(), searching = false) }; return }
        searchJob = viewModelScope.launch {
            delay(300)
            _ui.update { it.copy(searching = true) }
            try {
                val near = searchCenter()
                val r = runCatching {
                    c.search.search(q, near) { partial -> _ui.update { it.copy(results = partial) } }
                }.getOrDefault(emptyList())
                _ui.update { it.copy(results = r) }
            } finally {
                _ui.update { it.copy(searching = false) } // never leave the loading bar stuck
            }
        }
    }

    /** Where to search around: GPS, else the map centre, else the last known position. */
    private fun searchCenter(): LatLon = here ?: map?.center() ?: LatLon(c.prefs.lastLat, c.prefs.lastLon)

    fun searchCategory(cat: Category) {
        searchJob?.cancel()
        _ui.update { it.copy(screen = Screen.SEARCH, query = "", category = cat.label, searching = true, results = emptyList()) }
        searchJob = viewModelScope.launch {
            try {
                val near = searchCenter()
                val r = runCatching {
                    c.search.nearby(cat, near) { partial -> _ui.update { it.copy(results = partial) } }
                }.getOrDefault(emptyList())
                _ui.update { it.copy(results = r) }
                if (r.isEmpty()) toast(if (c.net.isOnline) "No ${cat.label.lowercase()} found nearby" else "Nothing found offline - download this area first")
            } finally {
                _ui.update { it.copy(searching = false) }
            }
        }
    }

    fun showResultsOnMap() {
        val r = _ui.value.results
        if (r.isEmpty()) return
        _ui.update { it.copy(screen = Screen.MAP, sheet = Sheet.None) }
        map?.setResults(r)
        map?.fit(r.map { it.latLon } + listOfNotNull(here), 200)
    }

    fun clearResults() {
        _ui.update { it.copy(results = emptyList(), query = "", category = null) }
        map?.setResults(emptyList())
    }

    // ---------------- places ----------------
    fun selectPlace(p: Place, fly: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) { c.db.addRecent(p) }
        _ui.update { it.copy(screen = Screen.MAP, sheet = Sheet.PlaceInfo(p), reportPicker = false) }
        map?.setRoutes(emptyList(), 0)
        map?.setPin(p)
        if (fly) map?.flyTo(p.latLon, 16.0)
    }

    private fun onMapClick(at: LatLon, poi: Place?, reportId: String?) {
        if (nav.value.active) {
            if (reportId != null) reports.value.firstOrNull { it.id == reportId }?.let { r -> _ui.update { it.copy(sheet = Sheet.ReportInfo(r)) } }
            return
        }
        when {
            reportId != null -> reports.value.firstOrNull { it.id == reportId }?.let { r -> _ui.update { it.copy(sheet = Sheet.ReportInfo(r)) } }
            poi != null -> {
                selectPlace(poi, fly = false)
                if (poi.address.isBlank()) viewModelScope.launch {
                    val rev = c.search.reverse(poi.latLon)
                    val cur = _ui.value.sheet
                    if (cur is Sheet.PlaceInfo && cur.place == poi && rev.address.isNotBlank()) {
                        _ui.update { it.copy(sheet = Sheet.PlaceInfo(poi.copy(address = rev.address))) }
                    }
                }
            }
            _ui.value.sheet is Sheet.Routes -> {}
            _ui.value.sheet != Sheet.None -> closeSheet()
        }
    }

    private fun onLongClick(at: LatLon) {
        if (nav.value.active || _ui.value.selectingArea) return
        val temp = Place("Dropped pin", "%.5f, %.5f".format(at.lat, at.lon), at.lat, at.lon, "pin", "pin")
        selectPlace(temp, fly = false)
        viewModelScope.launch {
            val p = c.search.reverse(at)
            val cur = _ui.value.sheet
            if (cur is Sheet.PlaceInfo && cur.place == temp) {
                val named = if (p.name == "Dropped pin" || p.name.isBlank()) temp.copy(address = p.address.ifBlank { temp.address })
                else p.copy(lat = at.lat, lon = at.lon)
                _ui.update { it.copy(sheet = Sheet.PlaceInfo(named)) }
            }
        }
    }

    fun closeSheet() {
        _ui.update { it.copy(sheet = Sheet.None) }
        if (!nav.value.active) {
            map?.setRoutes(emptyList(), 0)
            map?.setPin(null)
        }
    }

    fun savePlace(p: Place, kind: String) = viewModelScope.launch(Dispatchers.IO) {
        c.db.savePlace(kind, if (kind == "home") p.copy(name = "Home") else if (kind == "work") p.copy(name = "Work") else p)
        refreshLists()
        withContext(Dispatchers.Main) { toast(when (kind) { "home" -> "Saved as Home"; "work" -> "Saved as Work"; else -> "Saved to your places" }) }
    }

    fun deleteSaved(sp: SavedPlace) = viewModelScope.launch(Dispatchers.IO) { c.db.deletePlace(sp.id); refreshLists() }
    fun clearRecents() = viewModelScope.launch(Dispatchers.IO) { c.db.clearRecents(); refreshLists() }

    // ---------------- routing ----------------
    fun directions(dest: Place, mode: TravelMode = (ui.value.sheet as? Sheet.Routes)?.mode ?: TravelMode.DRIVE) {
        val from = here
        if (from == null) { toast("Waiting for GPS location..."); return }
        _ui.update { it.copy(screen = Screen.MAP, sheet = Sheet.Routes(dest, mode = mode)) }
        map?.setResults(emptyList())
        map?.setPin(dest)
        viewModelScope.launch {
            try {
                val routes = c.routing.routes(from, dest.latLon, mode, avoid = c.nav.closures())
                val cur = _ui.value.sheet
                if (cur is Sheet.Routes && cur.dest == dest) {
                    _ui.update { it.copy(sheet = cur.copy(routes = routes, selected = 0, loading = false, error = null)) }
                    map?.setRoutes(routes, 0)
                    map?.setPin(dest)
                    map?.fit(routes.flatMap { r -> r.points.filterIndexed { i, _ -> i % 10 == 0 } } + dest.latLon, 900)
                    if (mode == TravelMode.DRIVE && routes.first().distanceM < 400_000) {
                        val box = BBox.of(routes.first().points).expand(500.0)
                        launch(Dispatchers.IO) { c.offlineAreas.cacheCamerasFor(box); loadCameras() }
                    }
                }
            } catch (e: Exception) {
                val cur = _ui.value.sheet
                if (cur is Sheet.Routes && cur.dest == dest) _ui.update { it.copy(sheet = cur.copy(loading = false, error = e.message ?: "Routing failed")) }
            }
        }
    }

    fun selectRoute(i: Int) {
        val s = _ui.value.sheet as? Sheet.Routes ?: return
        _ui.update { it.copy(sheet = s.copy(selected = i)) }
        map?.setRoutes(s.routes, i)
    }

    fun setMode(mode: TravelMode) {
        val s = _ui.value.sheet as? Sheet.Routes ?: return
        if (s.mode != mode) directions(s.dest, mode)
    }

    fun startNavigation(simulate: Boolean = false) {
        val s = _ui.value.sheet as? Sheet.Routes ?: return
        val route = s.routes.getOrNull(s.selected) ?: return
        _ui.update { it.copy(sheet = Sheet.None, following = true, results = emptyList()) }
        map?.setResults(emptyList())
        // The nav collector switches the map into driving mode (same path as Android Auto).
        c.startNavigation(route, s.dest, simulate)
    }

    fun endNavigation() {
        c.endNavigation()
        _ui.update { it.copy(sheet = Sheet.None, following = true) }
        map?.recenter(false)
    }

    fun navigateHomeOrWork(kind: String) {
        val p = _ui.value.saved.firstOrNull { it.kind == kind }?.place
        if (p == null) { toast("Set your ${kind.replaceFirstChar { it.uppercase() }} from any place's Save button"); openScreen(Screen.SEARCH); return }
        directions(p)
    }

    fun recenter() {
        _ui.update { it.copy(following = true) }
        map?.recenter(settings.value.tilt3d)
    }

    fun overview() {
        val r = nav.value.route ?: return
        _ui.update { it.copy(following = false) }
        map?.fit(r.points.filterIndexed { i, _ -> i % 5 == 0 } + r.destination, 400)
    }

    // ---------------- reports ----------------
    fun openLayers(open: Boolean) { _ui.update { it.copy(layersSheet = open) } }

    fun setSatellite(on: Boolean) { updateSettings { it.copy(satellite = on) } }

    fun openReportPicker(open: Boolean) { _ui.update { it.copy(reportPicker = open) } }

    fun submitReport(t: ReportType) {
        val loc = c.location.location.value
        val at = loc?.let { LatLon(it.latitude, it.longitude) } ?: map?.center() ?: return
        _ui.update { it.copy(reportPicker = false) }
        viewModelScope.launch {
            c.reports.add(t, at, loc?.bearing?.toDouble() ?: 0.0)
            toast("Thanks! ${t.label} reported" + if (settings.value.reportServer.isBlank()) " (saved on this phone)" else "")
        }
    }

    fun vote(r: Report, stillThere: Boolean) {
        viewModelScope.launch {
            c.reports.vote(r, stillThere)
            toast(if (stillThere) "Thanks for confirming" else "Thanks - removed")
        }
        c.nav.dismissAlert()
        if (_ui.value.sheet is Sheet.ReportInfo) _ui.update { it.copy(sheet = Sheet.None) }
    }

    fun deleteReport(r: Report) {
        viewModelScope.launch { c.reports.delete(r) }
        _ui.update { it.copy(sheet = Sheet.None) }
    }

    // ---------------- map idle: cameras + area estimate ----------------
    private fun onIdle(b: BBox, zoom: Double) {
        _ui.update { it.copy(zoom = zoom) }
        if (_ui.value.selectingArea) {
            val inner = shrink(b)
            _ui.update { it.copy(areaBounds = inner, areaEstimate = c.offlineAreas.estimate(inner)) }
        }
        loadCameras()
    }

    private fun loadCameras() {
        val m = map ?: return
        viewModelScope.launch {
            val z = _ui.value.zoom
            if (z < 10.5 && !nav.value.active) { m.setCameras(emptyList()); return@launch }
            val b = withContext(Dispatchers.Main) { m.visibleBounds() }.expand(2000.0)
            val list = withContext(Dispatchers.IO) { c.db.cameras(b) }
            m.setCameras(list)
        }
    }

    private fun shrink(b: BBox): BBox {
        val dLat = (b.maxLat - b.minLat) * 0.08; val dLon = (b.maxLon - b.minLon) * 0.08
        return BBox(b.minLat + dLat * 2.2, b.minLon + dLon, b.maxLat - dLat * 1.6, b.maxLon - dLon)
    }

    // ---------------- offline areas ----------------
    fun beginAreaSelect() {
        _ui.update { it.copy(screen = Screen.MAP, selectingArea = true, sheet = Sheet.None) }
        map?.let { m ->
            val inner = shrink(m.visibleBounds())
            _ui.update { it.copy(areaBounds = inner, areaEstimate = c.offlineAreas.estimate(inner)) }
            // Start from a metro-sized area; zoom out to cover more (e.g. a whole state).
            if (m.map.cameraPosition.zoom > 11.5) m.map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomTo(11.0), 600)
        }
    }

    fun cancelAreaSelect() { _ui.update { it.copy(selectingArea = false, screen = Screen.OFFLINE) } }

    fun confirmArea(name: String) {
        val b = _ui.value.areaBounds ?: return
        c.offlineAreas.start(name.ifBlank { "My area" }, b)
        _ui.update { it.copy(selectingArea = false, screen = Screen.OFFLINE) }
        toast("Download started - you can keep using the app")
    }

    suspend fun suggestAreaName(): String {
        val center = _ui.value.areaBounds?.center ?: return "My area"
        return runCatching { c.search.reverse(center) }.getOrNull()?.let { p ->
            p.address.split(",").map { it.trim() }.firstOrNull { it.isNotBlank() && !it.first().isDigit() } ?: p.name
        }?.takeIf { it != "Dropped pin" } ?: "My area"
    }

    fun deleteArea(a: com.roadwise.app.core.OfflineArea) = viewModelScope.launch {
        c.offlineAreas.delete(a); toast("Deleted ${a.name}")
    }

    fun retryArea(a: com.roadwise.app.core.OfflineArea) = c.offlineAreas.retry(a)

    /** The state you're in right now (or were last), for the one-tap download. */
    fun myState(): com.roadwise.app.offline.Region? =
        com.roadwise.app.offline.stateAt(here ?: LatLon(c.prefs.lastLat, c.prefs.lastLon))

    fun estimateRegion(r: com.roadwise.app.offline.Region) = c.offlineAreas.estimate(r.bbox)

    fun dismissOfflinePrompt() {
        c.prefs.offlinePromptDismissed = true
        _ui.update { it.copy(offlinePromptHidden = true) }
        toast("No problem - tap Offline any time to download maps")
    }

    fun cancelDownload(a: com.roadwise.app.core.OfflineArea) = viewModelScope.launch {
        c.offlineAreas.cancel(a)
        toast("Cancelled ${a.name}")
    }

    fun downloadRegions(list: List<com.roadwise.app.offline.Region>) {
        val n = c.offlineAreas.startRegions(list)
        toast(when {
            n == 0 -> "Already downloaded"
            n == 1 -> "Downloading ${list.first().name} - you can keep using the app"
            else -> "Downloading $n states one after another"
        })
    }

    fun storageUsed(): Long = c.offlineAreas.storageUsedBytes()

    // ---------------- location sharing ----------------
    val liveShare = c.share.state

    fun openShareDialog(open: Boolean) { _ui.update { it.copy(shareDialog = open) } }

    fun shareMyLocation(ctx: android.content.Context) {
        val loc = c.location.location.value
        if (loc == null) { toast("Waiting for GPS location..."); return }
        _ui.update { it.copy(shareDialog = false) }
        c.share.shareIntent(ctx, c.share.locationText(loc))
    }

    fun shareTrip(ctx: android.content.Context, live: Boolean) {
        _ui.update { it.copy(shareDialog = false) }
        viewModelScope.launch {
            if (live && c.share.serverConfigured && !c.share.state.value.active) {
                try { c.share.start(0, untilArrival = true); startShareService() }
                catch (e: Exception) { toast("Live sharing unavailable: ${e.message}") }
            }
            c.share.shareIntent(ctx, c.share.tripText(nav.value))
        }
    }

    fun startLiveShare(ctx: android.content.Context, minutes: Int) {
        _ui.update { it.copy(shareDialog = false) }
        viewModelScope.launch {
            try {
                val url = c.share.start(minutes)
                startShareService()
                val until = if (minutes >= 60) "${minutes / 60} hr" else "$minutes min"
                c.share.shareIntent(ctx, "🔴 Follow my live location for the next $until: $url")
            } catch (e: Exception) {
                toast("Couldn't start live sharing: ${e.message}")
            }
        }
    }

    fun copyLiveLink(ctx: android.content.Context) {
        val url = c.share.state.value.url
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Live location", url))
        toast("Link copied")
    }

    fun stopLiveShare() { c.share.stop(); toast("Stopped sharing your location") }

    private fun startShareService() {
        val app = getApplication<Application>()
        ContextCompat.startForegroundService(app, Intent(app, com.roadwise.app.share.ShareService::class.java))
    }

    // ---------------- settings ----------------
    fun updateSettings(f: (Settings) -> Settings) {
        c.prefs.update(f)
        viewModelScope.launch { c.reports.sync(here) }
    }

    suspend fun testServer(url: String) = c.reports.testServer(url)

    fun distanceTo(p: LatLon): Double? = here?.let { Geo.dist(it, p) }
}
