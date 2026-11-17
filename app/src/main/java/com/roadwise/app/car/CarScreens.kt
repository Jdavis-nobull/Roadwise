package com.roadwise.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.Maneuver as CarManeuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Place
import com.roadwise.app.core.Units
import com.roadwise.app.reports.ReportType
import com.roadwise.app.routing.Maneuver
import com.roadwise.app.routing.Route
import com.roadwise.app.routing.Turn
import com.roadwise.app.search.CATEGORIES
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.TimeZone

/** Converts Roadwise values into Car App Library models. */
object CarFormat {
    fun distance(m: Double, imperial: Boolean): Distance {
        if (imperial) {
            val mi = m / 1609.344
            return when {
                mi >= 10 -> Distance.create(Math.round(mi).toDouble(), Distance.UNIT_MILES)
                mi >= 0.19 -> Distance.create(Math.round(mi * 10) / 10.0, Distance.UNIT_MILES_P1)
                else -> Distance.create((Math.round(m * 3.28084 / 50) * 50).toDouble().coerceAtLeast(10.0), Distance.UNIT_FEET)
            }
        }
        return when {
            m >= 10_000 -> Distance.create(Math.round(m / 1000).toDouble(), Distance.UNIT_KILOMETERS)
            m >= 1000 -> Distance.create(Math.round(m / 100) / 10.0, Distance.UNIT_KILOMETERS_P1)
            else -> Distance.create((Math.round(m / 10) * 10).toDouble(), Distance.UNIT_METERS)
        }
    }

    fun estimate(m: Double, sec: Double, etaMillis: Long, imperial: Boolean): TravelEstimate =
        TravelEstimate.Builder(distance(m, imperial), DateTimeWithZone.create(etaMillis, TimeZone.getDefault()))
            .setRemainingTimeSeconds(sec.toLong().coerceAtLeast(0))
            .build()

    fun step(m: Maneuver, imperial: Boolean): Step {
        val b = Step.Builder(m.text)
        b.setManeuver(maneuver(m))
        m.street?.let { b.setRoad(it.substringBefore(" / ")) }
        return b.build()
    }

    private fun maneuver(m: Maneuver): CarManeuver {
        val type = when (m.turn) {
            Turn.DEPART -> CarManeuver.TYPE_DEPART
            Turn.STRAIGHT -> CarManeuver.TYPE_STRAIGHT
            Turn.SLIGHT_LEFT -> CarManeuver.TYPE_TURN_SLIGHT_LEFT
            Turn.LEFT -> CarManeuver.TYPE_TURN_NORMAL_LEFT
            Turn.SHARP_LEFT -> CarManeuver.TYPE_TURN_SHARP_LEFT
            Turn.SLIGHT_RIGHT -> CarManeuver.TYPE_TURN_SLIGHT_RIGHT
            Turn.RIGHT -> CarManeuver.TYPE_TURN_NORMAL_RIGHT
            Turn.SHARP_RIGHT -> CarManeuver.TYPE_TURN_SHARP_RIGHT
            Turn.UTURN -> CarManeuver.TYPE_U_TURN_LEFT
            Turn.KEEP_LEFT -> CarManeuver.TYPE_KEEP_LEFT
            Turn.KEEP_RIGHT -> CarManeuver.TYPE_KEEP_RIGHT
            Turn.EXIT_LEFT, Turn.RAMP_LEFT -> CarManeuver.TYPE_OFF_RAMP_NORMAL_LEFT
            Turn.EXIT_RIGHT, Turn.RAMP_RIGHT -> CarManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            Turn.MERGE -> CarManeuver.TYPE_MERGE_SIDE_UNSPECIFIED
            Turn.ROUNDABOUT -> if (m.exit > 0) CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else CarManeuver.TYPE_ROUNDABOUT_ENTER_CCW
            Turn.FERRY -> CarManeuver.TYPE_FERRY_BOAT
            Turn.ARRIVE -> CarManeuver.TYPE_DESTINATION
        }
        val b = CarManeuver.Builder(type).setIcon(CarIcons.turn(m.turn))
        if (type == CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) b.setRoundaboutExitNumber(m.exit)
        return b.build()
    }

    fun minutes(sec: Double) = Units.duration(sec)
}

private fun action(icon: String, title: String? = null, onClick: () -> Unit): Action {
    val b = Action.Builder().setIcon(CarIcons.icon(icon)).setOnClickListener(onClick)
    if (title != null) b.setTitle(title)
    return b.build()
}

/** Main car screen: live map; turn-by-turn card while navigating. */
class CarMapScreen(car: CarContext, private val s: RoadwiseSession) : Screen(car), DefaultLifecycleObserver {
    private var job: Job? = null
    private var lastKey = ""

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) {
        // Refresh the card when guidance changes (throttled to what the host allows).
        job = lifecycle.coroutineScope.launch {
            s.c.nav.state.collect { st ->
                val key = "${st.active}|${st.arrived}|${st.rerouting}|${st.nextIndex}|${(st.distToNextM / 15).toInt()}|${(st.remainingS / 30).toInt()}|${st.alert?.key}"
                if (key != lastKey) { lastKey = key; invalidate(); delay(900) }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) { job?.cancel() }

    override fun onGetTemplate(): Template {
        val c = s.c
        val st = c.nav.state.value
        val imperial = c.prefs.current.imperial
        val strip = ActionStrip.Builder()
        if (st.active) {
            strip.addAction(action(CarIcons.REPORT, "Report") { screenManager.push(CarReportScreen(carContext, s)) })
            strip.addAction(Action.Builder().setTitle("End").setOnClickListener { c.endNavigation() }.build())
        } else {
            strip.addAction(action(CarIcons.SEARCH, "Search") { screenManager.push(CarSearchScreen(carContext, s)) })
            strip.addAction(action(CarIcons.REPORT, "Report") { screenManager.push(CarReportScreen(carContext, s)) })
        }
        val mapStrip = ActionStrip.Builder()
        if (runCatching { carContext.carAppApiLevel }.getOrDefault(1) >= 2) mapStrip.addAction(Action.PAN)
        mapStrip.addAction(action(CarIcons.MY_LOCATION) { s.renderer.recenter() })
        mapStrip.addAction(action(CarIcons.ZOOM_IN) { s.renderer.zoom(1.0) })
        mapStrip.addAction(action(CarIcons.ZOOM_OUT) { s.renderer.zoom(-1.0) })

        val b = NavigationTemplate.Builder().setActionStrip(strip.build()).setMapActionStrip(mapStrip.build())
        if (st.active) {
            when {
                st.arrived -> b.setNavigationInfo(MessageInfo.Builder("You've arrived").setText(st.destination?.name ?: "").build())
                st.rerouting -> b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
                st.next != null -> {
                    val ri = RoutingInfo.Builder().setCurrentStep(CarFormat.step(st.next, imperial), CarFormat.distance(st.distToNextM, imperial))
                    st.then?.let { ri.setNextStep(CarFormat.step(it, imperial)) }
                    b.setNavigationInfo(ri.build())
                }
            }
            if (!st.arrived) b.setDestinationTravelEstimate(CarFormat.estimate(st.remainingM, st.remainingS, st.etaMillis, imperial))
            st.alert?.let { a ->
                if (System.currentTimeMillis() < a.until && a.key != shownAlert) {
                    shownAlert = a.key
                    CarToast.makeText(carContext, "${a.title} · ${a.detail}", CarToast.LENGTH_LONG).show()
                }
            }
        }
        return b.build()
    }

    private var shownAlert: String? = null
}

/** Search box + saved places, recents and nearby categories. */
class CarSearchScreen(car: CarContext, private val s: RoadwiseSession, initial: String = "") : Screen(car) {
    private var query = initial
    private var results: List<Place>? = null
    private var loading = false
    private var job: Job? = null

    init { if (initial.isNotBlank()) search(initial) }

    private fun search(q: String) {
        query = q
        job?.cancel()
        if (q.trim().length < 2) { results = null; invalidate(); return }
        loading = true; invalidate()
        job = lifecycle.coroutineScope.launch {
            delay(400)
            val here = s.c.location.last ?: LatLon(s.c.prefs.lastLat, s.c.prefs.lastLon)
            results = runCatching { s.c.search.search(q, here) }.getOrDefault(emptyList())
            loading = false; invalidate()
        }
    }

    private fun category(i: Int) {
        val cat = CATEGORIES[i]
        loading = true; invalidate()
        job?.cancel()
        job = lifecycle.coroutineScope.launch {
            val here = s.c.location.last ?: LatLon(s.c.prefs.lastLat, s.c.prefs.lastLon)
            results = runCatching { s.c.search.nearby(cat, here) }.getOrDefault(emptyList())
            query = cat.label
            loading = false; invalidate()
        }
    }

    private fun row(p: Place, icon: String): Row {
        val here = s.c.location.last
        val dist = here?.let { Units.distance(com.roadwise.app.core.Geo.dist(it, p.latLon), s.c.prefs.current.imperial) }
        return Row.Builder().setTitle(p.name)
            .addText(listOfNotNull(dist, p.address.ifBlank { null }).joinToString(" · ").ifBlank { " " })
            .setImage(CarIcons.icon(icon))
            .setOnClickListener { screenManager.push(CarRoutePreviewScreen(carContext, s, p)) }
            .build()
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val res = results
        if (res != null) {
            if (res.isEmpty()) list.setNoItemsMessage(if (s.c.net.isOnline) "No results" else "No results offline - download this area on your phone")
            res.take(6).forEach { list.addItem(row(it, CarIcons.PLACE)) }
        } else {
            val saved = s.c.db.savedPlaces()
            saved.firstOrNull { it.kind == "home" }?.let { list.addItem(row(it.place, CarIcons.HOME)) }
            saved.firstOrNull { it.kind == "work" }?.let { list.addItem(row(it.place, CarIcons.WORK)) }
            listOf(0, 1, 2, 4).forEach { i ->
                val cat = CATEGORIES[i]
                list.addItem(Row.Builder().setTitle("${cat.emoji} ${cat.label} nearby").setOnClickListener { category(i) }.build())
            }
            s.c.db.recents().take(3).forEach { list.addItem(row(it, CarIcons.HISTORY)) }
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = search(searchText)
            override fun onSearchSubmitted(searchText: String) = search(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Where to?")
            .setInitialSearchText(query)
            .setShowKeyboardByDefault(false)
            .apply { if (loading) setLoading(true) else setItemList(list.build()) }
            .build()
    }
}

/** Shows route options on the map with a Go button. */
class CarRoutePreviewScreen(
    car: CarContext, private val s: RoadwiseSession, private val dest: Place,
    preloaded: List<Route>? = null,
) : Screen(car), DefaultLifecycleObserver {
    private var routes: List<Route>? = preloaded
    private var selected = 0
    private var error: String? = null

    init {
        lifecycle.addObserver(this)
        if (preloaded == null) lifecycle.coroutineScope.launch {
            val c = s.c
            val from = c.location.last
            if (from == null) { error = "Waiting for GPS"; invalidate(); return@launch }
            try {
                val r = c.routing.routes(from, dest.latLon, com.roadwise.app.routing.TravelMode.DRIVE, avoid = c.nav.closures())
                routes = r
                s.renderer.showPreview(r, 0, dest)
            } catch (e: Exception) { error = e.message ?: "Routing failed" }
            invalidate()
        }
        runCatching { s.c.db.addRecent(dest) }
    }

    override fun onDestroy(owner: LifecycleOwner) { s.renderer.clearPreview() }

    override fun onGetTemplate(): Template {
        val err = error
        if (err != null) {
            return androidx.car.app.model.MessageTemplate.Builder(err).setTitle(dest.name).setHeaderAction(Action.BACK).build()
        }
        val b = RoutePreviewNavigationTemplate.Builder().setTitle(dest.name).setHeaderAction(Action.BACK)
        val r = routes
        when {
            r == null -> b.setLoading(true)
            else -> {
                val imperial = s.c.prefs.current.imperial
                val list = ItemList.Builder().setOnSelectedListener { i ->
                    selected = i
                    s.renderer.showPreview(r, i, dest)
                    invalidate()
                }
                r.forEach { route ->
                    // Android Auto requires time/distance as spans so it can format them for the car.
                    val title = android.text.SpannableString("  ").apply {
                        setSpan(androidx.car.app.model.DurationSpan.create(route.durationS.toLong()), 0, 1, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    }
                    val text = android.text.SpannableString("  · ${route.label}" + if (route.hasTolls) " · Tolls" else "").apply {
                        setSpan(androidx.car.app.model.DistanceSpan.create(CarFormat.distance(route.distanceM, imperial)), 0, 1, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    }
                    list.addItem(Row.Builder().setTitle(title).addText(text).build())
                }
                list.setSelectedIndex(selected)
                b.setItemList(list.build())
                b.setNavigateAction(Action.Builder().setTitle("Go").setBackgroundColor(CarColor.BLUE).setOnClickListener {
                    r.getOrNull(selected)?.let { s.c.startNavigation(it, dest) }
                    screenManager.popToRoot()
                }.build())
            }
        }
        return b.build()
    }
}

/** One-tap hazard reports. */
class CarReportScreen(car: CarContext, private val s: RoadwiseSession) : Screen(car) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        ReportType.entries.take(6).forEach { t ->
            list.addItem(GridItem.Builder().setTitle(t.label).setImage(CarIcons.emoji(t.emoji, t.color.toInt()))
                .setOnClickListener {
                    val loc = s.c.location.location.value
                    if (loc == null) {
                        CarToast.makeText(carContext, "Waiting for GPS", CarToast.LENGTH_SHORT).show()
                    } else {
                        lifecycle.coroutineScope.launch {
                            s.c.reports.add(t, LatLon(loc.latitude, loc.longitude), loc.bearing.toDouble())
                        }
                        CarToast.makeText(carContext, "Thanks! ${t.label} reported", CarToast.LENGTH_SHORT).show()
                    }
                    screenManager.pop()
                }.build())
        }
        return GridTemplate.Builder().setTitle("Report").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}
