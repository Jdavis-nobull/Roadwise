package com.roadwise.app.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Place(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val category: String = "",
    val source: String = "",
) {
    val latLon get() = LatLon(lat, lon)
}

data class SavedPlace(val id: Long, val kind: String, val place: Place)

data class Report(
    val id: String,
    val serverId: String?,
    val type: String,
    val lat: Double,
    val lon: Double,
    val heading: Double,
    val note: String,
    val created: Long,
    val expires: Long,
    val up: Int,
    val down: Int,
    val mine: Boolean,
    val pending: Boolean,
    val by: String,
)

data class OfflineArea(
    val id: Long,
    val name: String,
    val bbox: BBox,
    val created: Long,
    val status: String,      // queued | downloading | ready | error
    val progress: Double,
    val step: String,
    val mapBytes: Long,
    val regionIds: String,
    val segments: String,
    val pois: Int,
    val cameras: Int,
    val error: String?,
)

data class SpeedCamera(val id: Long, val lat: Double, val lon: Double, val maxspeedKmh: Int)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "roadwise.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE places(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, name TEXT, address TEXT, lat REAL, lon REAL, category TEXT, created INTEGER)")
        db.execSQL("CREATE TABLE recents(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, address TEXT, lat REAL, lon REAL, category TEXT, used INTEGER, UNIQUE(name, lat, lon) ON CONFLICT REPLACE)")
        db.execSQL("CREATE TABLE reports(id TEXT PRIMARY KEY, serverId TEXT, type TEXT, lat REAL, lon REAL, heading REAL, note TEXT, created INTEGER, expires INTEGER, up INTEGER, down INTEGER, mine INTEGER, pending INTEGER, byName TEXT)")
        db.execSQL("CREATE TABLE areas(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, minLat REAL, minLon REAL, maxLat REAL, maxLon REAL, created INTEGER, status TEXT, progress REAL, step TEXT, mapBytes INTEGER, regionIds TEXT, segments TEXT, pois INTEGER, cameras INTEGER, error TEXT)")
        db.execSQL("CREATE TABLE cameras(id INTEGER PRIMARY KEY, lat REAL, lon REAL, maxspeed INTEGER)")
        db.execSQL("CREATE INDEX cameras_pos ON cameras(lat, lon)")
        db.execSQL("CREATE VIRTUAL TABLE poi_fts USING fts4(name, category, address, lat, lon, area, notindexed=lat, notindexed=lon, notindexed=area)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- saved places ----------
    fun savePlace(kind: String, p: Place): Long {
        val db = writableDatabase
        if (kind == "home" || kind == "work") db.delete("places", "kind=?", arrayOf(kind))
        return db.insert("places", null, ContentValues().apply {
            put("kind", kind); put("name", p.name); put("address", p.address)
            put("lat", p.lat); put("lon", p.lon); put("category", p.category)
            put("created", System.currentTimeMillis())
        })
    }

    fun deletePlace(id: Long) = writableDatabase.delete("places", "id=?", arrayOf(id.toString()))

    fun savedPlaces(): List<SavedPlace> = readableDatabase.rawQuery(
        "SELECT id, kind, name, address, lat, lon, category FROM places ORDER BY CASE kind WHEN 'home' THEN 0 WHEN 'work' THEN 1 ELSE 2 END, created DESC", null
    ).use { c -> c.map { SavedPlace(it.getLong(0), it.getString(1), Place(it.getString(2), it.getString(3) ?: "", it.getDouble(4), it.getDouble(5), it.getString(6) ?: "", "saved")) } }

    // ---------- recents ----------
    fun addRecent(p: Place) {
        writableDatabase.insert("recents", null, ContentValues().apply {
            put("name", p.name); put("address", p.address); put("lat", p.lat); put("lon", p.lon)
            put("category", p.category); put("used", System.currentTimeMillis())
        })
        writableDatabase.execSQL("DELETE FROM recents WHERE id NOT IN (SELECT id FROM recents ORDER BY used DESC LIMIT 40)")
    }

    fun recents(): List<Place> = readableDatabase.rawQuery(
        "SELECT name, address, lat, lon, category FROM recents ORDER BY used DESC LIMIT 25", null
    ).use { c -> c.map { Place(it.getString(0), it.getString(1) ?: "", it.getDouble(2), it.getDouble(3), it.getString(4) ?: "", "recent") } }

    fun clearRecents() = writableDatabase.delete("recents", null, null)

    // ---------- reports ----------
    fun upsertReport(r: Report) {
        writableDatabase.insertWithOnConflict("reports", null, ContentValues().apply {
            put("id", r.id); put("serverId", r.serverId); put("type", r.type); put("lat", r.lat); put("lon", r.lon)
            put("heading", r.heading); put("note", r.note); put("created", r.created); put("expires", r.expires)
            put("up", r.up); put("down", r.down); put("mine", if (r.mine) 1 else 0); put("pending", if (r.pending) 1 else 0)
            put("byName", r.by)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteReport(id: String) = writableDatabase.delete("reports", "id=?", arrayOf(id))

    fun activeReports(now: Long = System.currentTimeMillis()): List<Report> {
        writableDatabase.delete("reports", "expires < ?", arrayOf(now.toString()))
        return readableDatabase.rawQuery("SELECT * FROM reports", null).use { c -> c.map { it.toReport() } }
    }

    fun pendingReports(): List<Report> =
        readableDatabase.rawQuery("SELECT * FROM reports WHERE pending=1", null).use { c -> c.map { it.toReport() } }

    fun reportByServerId(sid: String): Report? =
        readableDatabase.rawQuery("SELECT * FROM reports WHERE serverId=?", arrayOf(sid)).use { c -> c.map { it.toReport() }.firstOrNull() }

    fun deleteServerReportsNotIn(bbox: BBox, keep: Set<String>) {
        val rows = readableDatabase.rawQuery(
            "SELECT id, serverId FROM reports WHERE serverId IS NOT NULL AND pending=0 AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf(bbox.minLat.toString(), bbox.maxLat.toString(), bbox.minLon.toString(), bbox.maxLon.toString())
        ).use { c -> c.map { it.getString(0) to it.getString(1) } }
        for ((id, sid) in rows) if (sid !in keep) deleteReport(id)
    }

    private fun Cursor.toReport() = Report(
        id = getString(getColumnIndexOrThrow("id")),
        serverId = getString(getColumnIndexOrThrow("serverId")),
        type = getString(getColumnIndexOrThrow("type")),
        lat = getDouble(getColumnIndexOrThrow("lat")),
        lon = getDouble(getColumnIndexOrThrow("lon")),
        heading = getDouble(getColumnIndexOrThrow("heading")),
        note = getString(getColumnIndexOrThrow("note")) ?: "",
        created = getLong(getColumnIndexOrThrow("created")),
        expires = getLong(getColumnIndexOrThrow("expires")),
        up = getInt(getColumnIndexOrThrow("up")),
        down = getInt(getColumnIndexOrThrow("down")),
        mine = getInt(getColumnIndexOrThrow("mine")) == 1,
        pending = getInt(getColumnIndexOrThrow("pending")) == 1,
        by = getString(getColumnIndexOrThrow("byName")) ?: "",
    )

    // ---------- offline areas ----------
    fun insertArea(name: String, b: BBox): Long = writableDatabase.insert("areas", null, ContentValues().apply {
        put("name", name); put("minLat", b.minLat); put("minLon", b.minLon); put("maxLat", b.maxLat); put("maxLon", b.maxLon)
        put("created", System.currentTimeMillis()); put("status", "queued"); put("progress", 0.0); put("step", "Waiting")
        put("mapBytes", 0L); put("regionIds", ""); put("segments", ""); put("pois", 0); put("cameras", 0)
    })

    fun updateArea(id: Long, values: ContentValues.() -> Unit) {
        writableDatabase.update("areas", ContentValues().apply(values), "id=?", arrayOf(id.toString()))
    }

    fun deleteArea(id: Long) {
        writableDatabase.delete("areas", "id=?", arrayOf(id.toString()))
        writableDatabase.execSQL("DELETE FROM poi_fts WHERE area=?", arrayOf(id.toString()))
    }

    fun areas(): List<OfflineArea> = readableDatabase.rawQuery("SELECT * FROM areas ORDER BY created DESC", null).use { c ->
        c.map {
            OfflineArea(
                id = it.getLong(it.getColumnIndexOrThrow("id")),
                name = it.getString(it.getColumnIndexOrThrow("name")),
                bbox = BBox(
                    it.getDouble(it.getColumnIndexOrThrow("minLat")), it.getDouble(it.getColumnIndexOrThrow("minLon")),
                    it.getDouble(it.getColumnIndexOrThrow("maxLat")), it.getDouble(it.getColumnIndexOrThrow("maxLon"))),
                created = it.getLong(it.getColumnIndexOrThrow("created")),
                status = it.getString(it.getColumnIndexOrThrow("status")),
                progress = it.getDouble(it.getColumnIndexOrThrow("progress")),
                step = it.getString(it.getColumnIndexOrThrow("step")) ?: "",
                mapBytes = it.getLong(it.getColumnIndexOrThrow("mapBytes")),
                regionIds = it.getString(it.getColumnIndexOrThrow("regionIds")) ?: "",
                segments = it.getString(it.getColumnIndexOrThrow("segments")) ?: "",
                pois = it.getInt(it.getColumnIndexOrThrow("pois")),
                cameras = it.getInt(it.getColumnIndexOrThrow("cameras")),
                error = it.getString(it.getColumnIndexOrThrow("error")),
            )
        }
    }

    // ---------- speed cameras ----------
    fun putCameras(list: List<SpeedCamera>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (c in list) db.insertWithOnConflict("cameras", null, ContentValues().apply {
                put("id", c.id); put("lat", c.lat); put("lon", c.lon); put("maxspeed", c.maxspeedKmh)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun cameras(b: BBox): List<SpeedCamera> = readableDatabase.rawQuery(
        "SELECT id, lat, lon, maxspeed FROM cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 2000",
        arrayOf(b.minLat.toString(), b.maxLat.toString(), b.minLon.toString(), b.maxLon.toString())
    ).use { c -> c.map { SpeedCamera(it.getLong(0), it.getDouble(1), it.getDouble(2), it.getInt(3)) } }

    // ---------- offline place index ----------
    fun indexPlaces(areaId: Long, rows: List<Place>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT INTO poi_fts(name, category, address, lat, lon, area) VALUES (?,?,?,?,?,?)")
            for (p in rows) {
                st.clearBindings()
                st.bindString(1, p.name); st.bindString(2, p.category); st.bindString(3, p.address)
                st.bindDouble(4, p.lat); st.bindDouble(5, p.lon); st.bindString(6, areaId.toString())
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun clearIndex(areaId: Long) = writableDatabase.execSQL("DELETE FROM poi_fts WHERE area=?", arrayOf(areaId.toString()))

    /** Full-text search of the offline index, nearest first. */
    fun searchIndex(query: String, near: LatLon?, limit: Int = 40): List<Place> {
        val tokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" ") { "$it*" }
        val rows = readableDatabase.rawQuery(
            "SELECT name, category, address, lat, lon FROM poi_fts WHERE poi_fts MATCH ? LIMIT 600", arrayOf(match)
        ).use { c -> c.map { Place(it.getString(0), it.getString(2) ?: "", it.getDouble(3), it.getDouble(4), it.getString(1) ?: "", "offline") } }
        return rankByDistance(rows, near, limit)
    }

    fun searchIndexCategory(categories: List<String>, near: LatLon, radiusM: Double, limit: Int = 40): List<Place> {
        val b = BBox.around(near, radiusM)
        val match = categories.joinToString(" OR ") { "category:$it" }
        val rows = readableDatabase.rawQuery(
            "SELECT name, category, address, lat, lon FROM poi_fts WHERE poi_fts MATCH ? AND CAST(lat AS REAL) BETWEEN ? AND ? AND CAST(lon AS REAL) BETWEEN ? AND ? LIMIT 2000",
            arrayOf(match, b.minLat.toString(), b.maxLat.toString(), b.minLon.toString(), b.maxLon.toString())
        ).use { c -> c.map { Place(it.getString(0), it.getString(2) ?: "", it.getDouble(3), it.getDouble(4), it.getString(1) ?: "", "offline") } }
        return rankByDistance(rows, near, limit)
    }

    fun nearestIndexed(p: LatLon, radiusM: Double): Place? {
        val b = BBox.around(p, radiusM)
        val rows = readableDatabase.rawQuery(
            "SELECT name, category, address, lat, lon FROM poi_fts WHERE CAST(lat AS REAL) BETWEEN ? AND ? AND CAST(lon AS REAL) BETWEEN ? AND ? LIMIT 200",
            arrayOf(b.minLat.toString(), b.maxLat.toString(), b.minLon.toString(), b.maxLon.toString())
        ).use { c -> c.map { Place(it.getString(0), it.getString(2) ?: "", it.getDouble(3), it.getDouble(4), it.getString(1) ?: "", "offline") } }
        return rows.filter { !it.category.startsWith("road") }.minByOrNull { Geo.dist(p, it.latLon) }
    }

    private fun rankByDistance(rows: List<Place>, near: LatLon?, limit: Int): List<Place> {
        val dedup = rows.distinctBy { "${it.name.lowercase()}|${(it.lat * 500).toInt()}|${(it.lon * 500).toInt()}" }
        return if (near == null) dedup.take(limit) else dedup.sortedBy { Geo.dist(near, it.latLon) }.take(limit)
    }

    fun indexSize(): Int = readableDatabase.rawQuery("SELECT count(*) FROM poi_fts", null).use { it.moveToFirst(); it.getInt(0) }
}

inline fun <T> Cursor.map(f: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count.coerceAtLeast(0))
    while (moveToNext()) out.add(f(this))
    return out
}
