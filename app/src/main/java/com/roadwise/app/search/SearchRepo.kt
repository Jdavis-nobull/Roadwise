package com.roadwise.app.search

import android.util.Log
import com.roadwise.app.core.Db
import com.roadwise.app.core.Geo
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Net
import com.roadwise.app.core.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

data class Category(
    val label: String, val emoji: String, val offlineClasses: List<String>, val overpass: String,
    /** OpenStreetMap key:value tags for the fast Photon nearby lookup. */
    val tags: List<String>,
)

val CATEGORIES = listOf(
    Category("Gas", "⛽", listOf("fuel"), "[amenity=fuel]", listOf("amenity:fuel")),
    Category("Food", "🍔", listOf("restaurant", "fast_food"), "[amenity~\"^(restaurant|fast_food)$\"]", listOf("amenity:restaurant", "amenity:fast_food")),
    Category("Coffee", "☕", listOf("cafe"), "[amenity=cafe]", listOf("amenity:cafe")),
    Category("Parking", "🅿️", listOf("parking"), "[amenity=parking]", listOf("amenity:parking")),
    Category("EV charging", "🔌", listOf("charging_station"), "[amenity=charging_station]", listOf("amenity:charging_station")),
    Category("Groceries", "🛒", listOf("grocery", "supermarket"), "[shop~\"^(supermarket|grocery|convenience)$\"]", listOf("shop:supermarket", "shop:grocery", "shop:convenience")),
    Category("Hotels", "🛏️", listOf("lodging"), "[tourism~\"^(hotel|motel)$\"]", listOf("tourism:hotel", "tourism:motel")),
    Category("Hospitals", "🏥", listOf("hospital"), "[amenity=hospital]", listOf("amenity:hospital")),
)

class SearchRepo(private val net: Net, private val db: Db) {

    /**
     * Text search. Saved places and the offline index answer instantly through [onPartial];
     * the online lookup is merged in when it arrives and is never allowed to hang the screen.
     */
    suspend fun search(query: String, near: LatLon?, onPartial: (List<Place>) -> Unit = {}): List<Place> = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope emptyList()
        val coords = parseCoordinates(q)
        if (coords != null) return@coroutineScope listOf(Place("${"%.5f".format(coords.lat)}, ${"%.5f".format(coords.lon)}", "Coordinates", coords.lat, coords.lon, "coords", "coords"))

        val online = async {
            if (net.isOnline) withTimeoutOrNull(ONLINE_TIMEOUT_MS) { runCatching { photon(q, near) }.onFailure { Log.w(TAG, "photon", it) }.getOrNull() }
                ?: emptyList()
            else emptyList()
        }
        val local = withContext(Dispatchers.IO) {
            val saved = db.savedPlaces().filter { it.place.name.contains(q, true) || it.place.address.contains(q, true) }.map { it.place }
            saved + runCatching { db.searchIndex(q, near, 25) }.getOrDefault(emptyList())
        }
        if (local.isNotEmpty()) onPartial(dedupe(local, 1000))
        dedupe(local.take(local.size.coerceAtMost(3)) + online.await() + local.drop(3), 1000).take(30)
    }

    /**
     * "Gas / Food / Coffee…" near you. Offline results show instantly; the fast Photon lookup
     * (well under a second) fills in the rest. Results are cached for a few minutes.
     */
    suspend fun nearby(cat: Category, near: LatLon, onPartial: (List<Place>) -> Unit = {}): List<Place> = coroutineScope {
        val key = "${cat.label}|${(near.lat * 100).toInt()}|${(near.lon * 100).toInt()}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return@coroutineScope it.second }

        val online = async {
            if (!net.isOnline) return@async emptyList()
            withTimeoutOrNull(ONLINE_TIMEOUT_MS) {
                runCatching { photonNearby(cat, near) }.onFailure { Log.w(TAG, "photon nearby", it) }.getOrNull()
            } ?: emptyList()
        }
        val off = withContext(Dispatchers.IO) {
            runCatching { db.searchIndexCategory(cat.offlineClasses, near, 25_000.0) }.getOrDefault(emptyList())
        }.map { it.copy(category = cat.label) }
        if (off.isNotEmpty()) onPartial(off)

        var on = online.await()
        // Photon unavailable: one quick try of Overpass, strictly time-limited.
        if (on.isEmpty() && off.size < 5 && net.isOnline) {
            on = withTimeoutOrNull(OVERPASS_TIMEOUT_MS) {
                runCatching { overpass(cat, near) }.onFailure { Log.w(TAG, "overpass", it) }.getOrNull()
            } ?: emptyList()
        }
        val all = dedupe(on + off, 2000).sortedBy { Geo.dist(near, it.latLon) }.take(40)
        if (on.isNotEmpty()) cache[key] = System.currentTimeMillis() to all
        all
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Place>>>()

    private fun dedupe(list: List<Place>, grid: Int) =
        list.distinctBy { "${it.name.lowercase()}|${(it.lat * grid).toInt()}|${(it.lon * grid).toInt()}" }

    private suspend fun photonNearby(cat: Category, near: LatLon): List<Place> {
        fun url(radiusKm: Int) = buildString {
            append("https://photon.komoot.io/reverse?lat=${near.lat}&lon=${near.lon}&limit=30&radius=$radiusKm&lang=en")
            cat.tags.forEach { append("&osm_tag=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        fun parse(txt: String): List<Place> {
            val f = JSONObject(txt).getJSONArray("features")
            return (0 until f.length()).map { photonPlace(f.getJSONObject(it)).copy(category = cat.label) }
                .filter { it.name.isNotBlank() }
        }
        var res = parse(net.get(url(10)))
        // Sparse categories (e.g. EV chargers): widen the circle once.
        if (res.size < 5) res = (res + runCatching { parse(net.get(url(40))) }.getOrDefault(emptyList()))
        return res.sortedBy { Geo.dist(near, it.latLon) }
    }

    suspend fun reverse(p: LatLon): Place {
        if (net.isOnline) {
            runCatching {
                val txt = net.get("https://photon.komoot.io/reverse?lat=${p.lat}&lon=${p.lon}&lang=en&limit=1")
                val f = JSONObject(txt).getJSONArray("features")
                if (f.length() > 0) {
                    val pl = photonPlace(f.getJSONObject(0))
                    return pl.copy(lat = p.lat, lon = p.lon, name = pl.name.ifBlank { pl.address.substringBefore(",") })
                }
            }.onFailure { Log.w(TAG, "reverse", it) }
        }
        val near = withContext(Dispatchers.IO) {
            runCatching { db.nearestIndexed(p, 120.0) }.getOrNull()
        }
        return near?.takeIf { Geo.dist(p, it.latLon) < 120 }?.copy(lat = p.lat, lon = p.lon)
            ?: Place("Dropped pin", "%.5f, %.5f".format(p.lat, p.lon), p.lat, p.lon, "pin", "pin")
    }

    private suspend fun photon(q: String, near: LatLon?): List<Place> {
        val sb = StringBuilder("https://photon.komoot.io/api/?q=").append(URLEncoder.encode(q, "UTF-8")).append("&limit=15&lang=en")
        if (near != null) sb.append("&lat=${near.lat}&lon=${near.lon}&location_bias_scale=0.3")
        val f = JSONObject(net.get(sb.toString())).getJSONArray("features")
        return (0 until f.length()).map { photonPlace(f.getJSONObject(it)) }
    }

    private fun photonPlace(feat: JSONObject): Place {
        val pr = feat.getJSONObject("properties")
        val c = feat.getJSONObject("geometry").getJSONArray("coordinates")
        val street = listOfNotNull(pr.optString("housenumber").ifBlank { null }, pr.optString("street").ifBlank { null }).joinToString(" ")
        val city = pr.optString("city").ifBlank { pr.optString("locality").ifBlank { pr.optString("county") } }
        val addr = listOf(street, city, pr.optString("state"), pr.optString("country").takeIf { it != "United States" } ?: "")
            .filter { it.isNotBlank() }.distinct().joinToString(", ")
        val name = pr.optString("name").ifBlank { street.ifBlank { city } }
        val cat = pr.optString("osm_value").replace('_', ' ').takeIf { it != "yes" && it != "no" } ?: pr.optString("osm_key").replace('_', ' ')
        return Place(name, addr, c.getDouble(1), c.getDouble(0), cat, "online")
    }

    private suspend fun overpass(cat: Category, near: LatLon): List<Place> {
        val r = 15000
        val q = "[out:json][timeout:20];(node${cat.overpass}(around:$r,${near.lat},${near.lon});" +
            "way${cat.overpass}(around:$r,${near.lat},${near.lon}););out center 60;"
        val json = JSONObject(net.overpass(q))
        val els = json.getJSONArray("elements")
        val out = ArrayList<Place>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val lat = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
            val name = tags.optString("name").ifBlank { tags.optString("brand").ifBlank { cat.label } }
            val addr = listOf(
                listOf(tags.optString("addr:housenumber"), tags.optString("addr:street")).filter { it.isNotBlank() }.joinToString(" "),
                tags.optString("addr:city"),
            ).filter { it.isNotBlank() }.joinToString(", ")
            out += Place(name, addr, lat, lon, cat.label, "online")
        }
        return out.sortedBy { Geo.dist(near, it.latLon) }
    }

    private fun parseCoordinates(q: String): LatLon? {
        val m = Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[, ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$").find(q) ?: return null
        val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
    }

    companion object {
        const val TAG = "SearchRepo"
        const val OVERPASS = "https://overpass-api.de/api/interpreter"
        const val ONLINE_TIMEOUT_MS = 8_000L
        const val OVERPASS_TIMEOUT_MS = 10_000L
        const val CACHE_MS = 5 * 60_000L
    }
}
