package com.nesktf.guemaps.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.nesktf.guemaps.data.model.BusGroupsResponse
import com.nesktf.guemaps.data.model.BusRouteResponse
import com.nesktf.guemaps.data.model.BusStopRecord
import com.nesktf.guemaps.data.model.FlatBusLine
import com.nesktf.guemaps.data.model.NewsArticle
import com.nesktf.guemaps.data.model.SavedCard
import com.nesktf.guemaps.data.model.SellingPoint
import com.nesktf.guemaps.data.model.TarifaItem
import com.nesktf.guemaps.data.model.computeRouteHash
import com.nesktf.guemaps.data.model.sortedByMostRecent

class GuemapsDatabase(
    context: Context,
    private val gson: Gson = Gson()
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "guemaps.db"
        const val DATABASE_VERSION = 6

        private const val TABLE_BUS_GROUPS = "bus_groups_cache"
        private const val COL_BG_ID = "id"
        private const val COL_BG_JSON = "data_json"
        private const val COL_BG_UPDATED_AT = "updated_at"

        private const val TABLE_BUS_ROUTES = "bus_routes_cache"
        private const val COL_BR_LINE_ID = "line_id"
        private const val COL_BR_JSON = "route_json"
        private const val COL_BR_ROUTE_HASH = "route_hash"
        private const val COL_BR_UPDATED_AT = "updated_at"

        private const val TABLE_BUS_STOPS = "bus_stops"
        private const val COL_BS_ID = "id"
        private const val COL_BS_LINE_ID = "line_id"
        private const val COL_BS_STOP_CODE = "stop_code"
        private const val COL_BS_STOP_NAME = "stop_name"
        private const val COL_BS_LAT = "latitude"
        private const val COL_BS_LON = "longitude"

        private const val TABLE_RECENT_CARDS = "recent_cards"
        private const val COL_RC_CARD_NUMBER = "card_number"
        private const val COL_RC_ALIAS = "alias"
        private const val COL_RC_LAST_CHECKED = "last_checked"

        private const val TABLE_FAVORITE_LINES = "favorite_bus_lines"
        private const val COL_FL_CODE = "cod_linea"
        private const val COL_FL_DESC = "descripcion"
        private const val COL_FL_PATH = "group_path"
        private const val COL_FL_ADDED_AT = "added_at"

        private const val TABLE_FAVORITE_CARDS = "favorite_cards"
        private const val COL_FC_CARD_NUMBER = "card_number"
        private const val COL_FC_ALIAS = "alias"
        private const val COL_FC_ADDED_AT = "added_at"

        private const val TABLE_SELLING_POINTS = "selling_points"
        private const val COL_SP_ID = "id"
        private const val COL_SP_TIPO = "tipo"
        private const val COL_SP_NOMBRE = "nombre"
        private const val COL_SP_DOMICILIO = "domicilio"
        private const val COL_SP_DETALLE = "detalle_domicilio"
        private const val COL_SP_LAT = "latitude"
        private const val COL_SP_LON = "longitude"
        private const val COL_SP_UPDATED_AT = "updated_at"

        private const val TABLE_TARIFAS = "tarifas_cache"
        private const val COL_TC_ID = "id"
        private const val COL_TC_JSON = "tarifas_json"
        private const val COL_TC_UPDATED_AT = "updated_at"

        private const val TABLE_NEWS_ARTICLES = "news_articles"
        private const val COL_NA_ID = "id"
        private const val COL_NA_TITLE = "title"
        private const val COL_NA_DATE = "date_str"
        private const val COL_NA_SUMMARY = "summary"
        private const val COL_NA_IMAGE_URL = "image_url"
        private const val COL_NA_CONTENT_HTML = "content_html"
        private const val COL_NA_IS_READ = "is_read"
        private const val COL_NA_CACHED_AT = "cached_at"
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        onCreate(db)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_BUS_GROUPS (
                $COL_BG_ID INTEGER PRIMARY KEY,
                $COL_BG_JSON TEXT NOT NULL,
                $COL_BG_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_BUS_ROUTES (
                $COL_BR_LINE_ID TEXT PRIMARY KEY,
                $COL_BR_JSON TEXT NOT NULL,
                $COL_BR_ROUTE_HASH TEXT NOT NULL DEFAULT '',
                $COL_BR_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_BUS_STOPS (
                $COL_BS_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_BS_LINE_ID TEXT NOT NULL,
                $COL_BS_STOP_CODE TEXT NOT NULL,
                $COL_BS_STOP_NAME TEXT NOT NULL,
                $COL_BS_LAT REAL NOT NULL,
                $COL_BS_LON REAL NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bus_stops_line ON $TABLE_BUS_STOPS ($COL_BS_LINE_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bus_stops_name ON $TABLE_BUS_STOPS ($COL_BS_STOP_NAME)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bus_stops_code ON $TABLE_BUS_STOPS ($COL_BS_STOP_CODE)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_RECENT_CARDS (
                $COL_RC_CARD_NUMBER TEXT PRIMARY KEY,
                $COL_RC_ALIAS TEXT,
                $COL_RC_LAST_CHECKED INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FAVORITE_LINES (
                $COL_FL_CODE TEXT PRIMARY KEY,
                $COL_FL_DESC TEXT NOT NULL,
                $COL_FL_PATH TEXT NOT NULL,
                $COL_FL_ADDED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FAVORITE_CARDS (
                $COL_FC_CARD_NUMBER TEXT PRIMARY KEY,
                $COL_FC_ALIAS TEXT,
                $COL_FC_ADDED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SELLING_POINTS (
                $COL_SP_ID INTEGER PRIMARY KEY,
                $COL_SP_TIPO INTEGER,
                $COL_SP_NOMBRE TEXT NOT NULL,
                $COL_SP_DOMICILIO TEXT NOT NULL,
                $COL_SP_DETALLE TEXT,
                $COL_SP_LAT REAL,
                $COL_SP_LON REAL,
                $COL_SP_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TARIFAS (
                $COL_TC_ID INTEGER PRIMARY KEY,
                $COL_TC_JSON TEXT NOT NULL,
                $COL_TC_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NEWS_ARTICLES (
                $COL_NA_ID TEXT PRIMARY KEY,
                $COL_NA_TITLE TEXT NOT NULL,
                $COL_NA_DATE TEXT NOT NULL,
                $COL_NA_SUMMARY TEXT NOT NULL,
                $COL_NA_IMAGE_URL TEXT NOT NULL,
                $COL_NA_CONTENT_HTML TEXT,
                $COL_NA_IS_READ INTEGER NOT NULL DEFAULT 0,
                $COL_NA_CACHED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_BUS_ROUTES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_BUS_STOPS")
        }
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_BUS_STOPS")
            try {
                db.execSQL("UPDATE $TABLE_BUS_ROUTES SET $COL_BR_ROUTE_HASH = ''")
            } catch (_: Exception) {}
        }
        if (oldVersion < 5) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SELLING_POINTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_TARIFAS")
        }
        onCreate(db)
    }

    // --- Bus Groups Cache ---

    fun saveBusGroups(response: BusGroupsResponse) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_BG_ID, 1)
            put(COL_BG_JSON, gson.toJson(response))
            put(COL_BG_UPDATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_BUS_GROUPS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getCachedBusGroups(): BusGroupsResponse? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_BUS_GROUPS,
            arrayOf(COL_BG_JSON),
            "$COL_BG_ID = ?",
            arrayOf("1"),
            null,
            null,
            null
        )
        return cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(0)
                try {
                    gson.fromJson(json, BusGroupsResponse::class.java)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

    // --- Bus Routes & Stops Cache ---

    fun getStoredRouteHash(lineId: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_BUS_ROUTES,
            arrayOf(COL_BR_ROUTE_HASH),
            "$COL_BR_LINE_ID = ?",
            arrayOf(lineId),
            null,
            null,
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /**
     * Saves route response and synchronizes stops in the database.
     * Uses computeRouteHash to check if stops changed.
     * @return true if the route and its stops were updated or inserted, false if identical stops were already stored.
     */
    fun saveBusRouteAndSyncStops(lineId: String, routeResponse: BusRouteResponse): Boolean {
        val newHash = computeRouteHash(routeResponse)
        val oldHash = getStoredRouteHash(lineId)
        if (!oldHash.isNullOrEmpty() && oldHash == newHash) {
            // Route stops have not changed; avoid rebuilding stops table
            return false
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            // 1. Save or update route cache with new hash
            val routeValues = ContentValues().apply {
                put(COL_BR_LINE_ID, lineId)
                put(COL_BR_JSON, gson.toJson(routeResponse))
                put(COL_BR_ROUTE_HASH, newHash)
                put(COL_BR_UPDATED_AT, System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_BUS_ROUTES, null, routeValues, SQLiteDatabase.CONFLICT_REPLACE)

            // 2. Remove old stops for this line
            db.delete(TABLE_BUS_STOPS, "$COL_BS_LINE_ID = ?", arrayOf(lineId))

            // 3. Insert fresh stops
            val stops = routeResponse.nodos?.filter { it.parada } ?: emptyList()
            for (stop in stops) {
                val stopCode = stop.codigoParada?.trim() ?: ""
                val stopName = stop.cleanDescripcionParada
                if (stopName.isNotBlank() || stopCode.isNotBlank()) {
                    val stopValues = ContentValues().apply {
                        put(COL_BS_LINE_ID, lineId)
                        put(COL_BS_STOP_CODE, stopCode)
                        put(COL_BS_STOP_NAME, stopName)
                        put(COL_BS_LAT, stop.latitud)
                        put(COL_BS_LON, stop.longitud)
                    }
                    db.insert(TABLE_BUS_STOPS, null, stopValues)
                }
            }

            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun saveBusRoute(lineId: String, routeResponse: BusRouteResponse) {
        saveBusRouteAndSyncStops(lineId, routeResponse)
    }

    fun getCachedBusRoute(lineId: String): BusRouteResponse? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_BUS_ROUTES,
            arrayOf(COL_BR_JSON),
            "$COL_BR_LINE_ID = ?",
            arrayOf(lineId),
            null,
            null,
            null
        )
        return cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(0)
                try {
                    gson.fromJson(json, BusRouteResponse::class.java)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

    fun searchBusStops(query: String, limit: Int = 60): List<BusStopRecord> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val db = readableDatabase
        val pattern = "%$clean%"
        val cursor = db.rawQuery(
            """
            SELECT $COL_BS_LINE_ID, $COL_BS_STOP_CODE, $COL_BS_STOP_NAME, $COL_BS_LAT, $COL_BS_LON
            FROM $TABLE_BUS_STOPS
            WHERE $COL_BS_STOP_NAME LIKE ? OR $COL_BS_STOP_CODE LIKE ?
            LIMIT ?
            """.trimIndent(),
            arrayOf(pattern, pattern, limit.toString())
        )
        val list = mutableListOf<BusStopRecord>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    BusStopRecord(
                        lineId = it.getString(0),
                        stopCode = it.getString(1),
                        stopName = it.getString(2),
                        latitude = it.getDouble(3),
                        longitude = it.getDouble(4)
                    )
                )
            }
        }
        return list
    }

    fun getAllCachedRouteLineIds(): Set<String> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_BUS_ROUTES,
            arrayOf(COL_BR_LINE_ID),
            null,
            null,
            null,
            null,
            null
        )
        val set = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                set.add(it.getString(0))
            }
        }
        return set
    }

    fun getCachedRoutesCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_BUS_ROUTES", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun getCachedStopsCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_BUS_STOPS", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // --- Recent Cards ---

    fun saveRecentCard(cardNumber: String, alias: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_RC_CARD_NUMBER, cardNumber)
            put(COL_RC_ALIAS, alias)
            put(COL_RC_LAST_CHECKED, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_RECENT_CARDS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getRecentCards(limit: Int = 5): List<SavedCard> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_RECENT_CARDS,
            arrayOf(COL_RC_CARD_NUMBER, COL_RC_ALIAS, COL_RC_LAST_CHECKED),
            null,
            null,
            null,
            null,
            "$COL_RC_LAST_CHECKED DESC",
            limit.toString()
        )
        val list = mutableListOf<SavedCard>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SavedCard(
                        cardNumber = it.getString(0),
                        alias = if (it.isNull(1)) null else it.getString(1),
                        lastChecked = it.getLong(2)
                    )
                )
            }
        }
        return list
    }

    // --- Favorite Bus Lines ---

    fun addFavoriteLine(line: FlatBusLine) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_FL_CODE, line.codLinea)
            put(COL_FL_DESC, line.descripcion)
            put(COL_FL_PATH, line.groupPath)
            put(COL_FL_ADDED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_FAVORITE_LINES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeFavoriteLine(lineCode: String) {
        val db = writableDatabase
        db.delete(TABLE_FAVORITE_LINES, "$COL_FL_CODE = ?", arrayOf(lineCode))
    }

    fun isFavoriteLine(lineCode: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FAVORITE_LINES,
            arrayOf(COL_FL_CODE),
            "$COL_FL_CODE = ?",
            arrayOf(lineCode),
            null,
            null,
            null
        )
        return cursor.use { it.moveToFirst() }
    }

    fun getFavoriteLines(): List<FlatBusLine> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FAVORITE_LINES,
            arrayOf(COL_FL_CODE, COL_FL_DESC, COL_FL_PATH),
            null,
            null,
            null,
            null,
            "$COL_FL_ADDED_AT ASC"
        )
        val list = mutableListOf<FlatBusLine>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    FlatBusLine(
                        codLinea = it.getString(0),
                        descripcion = it.getString(1),
                        groupPath = it.getString(2)
                    )
                )
            }
        }
        return list
    }

    // --- Favorite Cards ---

    fun addFavoriteCard(cardNumber: String, alias: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_FC_CARD_NUMBER, cardNumber)
            put(COL_FC_ALIAS, alias)
            put(COL_FC_ADDED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_FAVORITE_CARDS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeFavoriteCard(cardNumber: String) {
        val db = writableDatabase
        db.delete(TABLE_FAVORITE_CARDS, "$COL_FC_CARD_NUMBER = ?", arrayOf(cardNumber))
    }

    fun isFavoriteCard(cardNumber: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FAVORITE_CARDS,
            arrayOf(COL_FC_CARD_NUMBER),
            "$COL_FC_CARD_NUMBER = ?",
            arrayOf(cardNumber),
            null,
            null,
            null
        )
        return cursor.use { it.moveToFirst() }
    }

    fun getFavoriteCards(): List<SavedCard> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FAVORITE_CARDS,
            arrayOf(COL_FC_CARD_NUMBER, COL_FC_ALIAS, COL_FC_ADDED_AT),
            null,
            null,
            null,
            null,
            "$COL_FC_ADDED_AT ASC"
        )
        val list = mutableListOf<SavedCard>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SavedCard(
                        cardNumber = it.getString(0),
                        alias = if (it.isNull(1)) null else it.getString(1),
                        lastChecked = it.getLong(2)
                    )
                )
            }
        }
        return list
    }

    // --- Selling Points Cache ---

    fun saveSellingPoints(points: List<SellingPoint>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_SELLING_POINTS, null, null)
            for (point in points) {
                val values = ContentValues().apply {
                    put(COL_SP_ID, point.id)
                    put(COL_SP_TIPO, point.tipo)
                    put(COL_SP_NOMBRE, point.nombre)
                    put(COL_SP_DOMICILIO, point.domicilio)
                    put(COL_SP_DETALLE, point.detalleDomicilio)
                    put(COL_SP_LAT, point.latitud)
                    put(COL_SP_LON, point.longitud)
                    put(COL_SP_UPDATED_AT, System.currentTimeMillis())
                }
                db.insertWithOnConflict(TABLE_SELLING_POINTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getSellingPoints(): List<SellingPoint> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SELLING_POINTS,
            arrayOf(COL_SP_ID, COL_SP_TIPO, COL_SP_NOMBRE, COL_SP_DOMICILIO, COL_SP_DETALLE, COL_SP_LAT, COL_SP_LON),
            null,
            null,
            null,
            null,
            "$COL_SP_NOMBRE ASC"
        )
        val list = mutableListOf<SellingPoint>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SellingPoint(
                        id = it.getLong(0),
                        tipo = if (it.isNull(1)) null else it.getInt(1),
                        nombre = it.getString(2) ?: "",
                        domicilio = it.getString(3) ?: "",
                        detalleDomicilio = if (it.isNull(4)) null else it.getString(4),
                        latitud = if (it.isNull(5)) null else it.getDouble(5),
                        longitud = if (it.isNull(6)) null else it.getDouble(6)
                    )
                )
            }
        }
        return list
    }

    // --- Tarifas Cache ---

    fun saveTarifas(tarifas: List<TarifaItem>) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TC_ID, 1)
            put(COL_TC_JSON, gson.toJson(tarifas))
            put(COL_TC_UPDATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_TARIFAS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getCachedTarifas(): List<TarifaItem>? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TARIFAS,
            arrayOf(COL_TC_JSON),
            "$COL_TC_ID = 1",
            null,
            null,
            null,
            null
        )
        return cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(0)
                val type = object : com.google.gson.reflect.TypeToken<List<TarifaItem>>() {}.type
                try {
                    gson.fromJson<List<TarifaItem>>(json, type)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

    // --- News Articles Cache ---

    fun saveNewsArticles(articles: List<NewsArticle>, maxKeep: Int = 16) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (article in articles.take(maxKeep)) {
                val cursor = db.query(
                    TABLE_NEWS_ARTICLES,
                    arrayOf(COL_NA_IS_READ, COL_NA_CONTENT_HTML),
                    "$COL_NA_ID = ?",
                    arrayOf(article.id),
                    null, null, null
                )
                var existingIsRead = article.isRead
                var existingContent = article.contentHtml
                cursor.use {
                    if (it.moveToFirst()) {
                        existingIsRead = it.getInt(0) == 1
                        if (existingContent == null && !it.isNull(1)) {
                            existingContent = it.getString(1)
                        }
                    }
                }

                val values = ContentValues().apply {
                    put(COL_NA_ID, article.id)
                    put(COL_NA_TITLE, article.title)
                    put(COL_NA_DATE, article.date)
                    put(COL_NA_SUMMARY, article.summary)
                    put(COL_NA_IMAGE_URL, article.imageUrl)
                    put(COL_NA_CONTENT_HTML, existingContent)
                    put(COL_NA_IS_READ, if (existingIsRead) 1 else 0)
                    put(COL_NA_CACHED_AT, article.cachedAt)
                }
                db.insertWithOnConflict(TABLE_NEWS_ARTICLES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }

            val keepIds = articles.take(maxKeep).map { it.id }
            if (keepIds.isNotEmpty()) {
                val placeholders = keepIds.joinToString(",") { "?" }
                db.delete(TABLE_NEWS_ARTICLES, "$COL_NA_ID NOT IN ($placeholders)", keepIds.toTypedArray())
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getNewsArticles(): List<NewsArticle> {
        val db = readableDatabase
        val list = mutableListOf<NewsArticle>()
        val cursor = db.query(
            TABLE_NEWS_ARTICLES,
            null,
            null,
            null,
            null,
            null,
            "CAST($COL_NA_ID AS INTEGER) DESC"
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_NA_ID)
            val titleIdx = it.getColumnIndexOrThrow(COL_NA_TITLE)
            val dateIdx = it.getColumnIndexOrThrow(COL_NA_DATE)
            val summaryIdx = it.getColumnIndexOrThrow(COL_NA_SUMMARY)
            val imgIdx = it.getColumnIndexOrThrow(COL_NA_IMAGE_URL)
            val contentIdx = it.getColumnIndexOrThrow(COL_NA_CONTENT_HTML)
            val readIdx = it.getColumnIndexOrThrow(COL_NA_IS_READ)
            val cachedIdx = it.getColumnIndexOrThrow(COL_NA_CACHED_AT)

            while (it.moveToNext()) {
                list.add(
                    NewsArticle(
                        id = it.getString(idIdx),
                        title = it.getString(titleIdx),
                        date = it.getString(dateIdx),
                        summary = it.getString(summaryIdx),
                        imageUrl = it.getString(imgIdx),
                        contentHtml = if (!it.isNull(contentIdx)) it.getString(contentIdx) else null,
                        isRead = it.getInt(readIdx) == 1,
                        cachedAt = it.getLong(cachedIdx)
                    )
                )
            }
        }
        return list.sortedByMostRecent()
    }

    fun updateArticleContent(id: String, contentHtml: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NA_CONTENT_HTML, contentHtml)
        }
        db.update(TABLE_NEWS_ARTICLES, values, "$COL_NA_ID = ?", arrayOf(id))
    }

    fun markArticleAsRead(id: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NA_IS_READ, 1)
        }
        db.update(TABLE_NEWS_ARTICLES, values, "$COL_NA_ID = ?", arrayOf(id))
    }

    fun markAllArticlesAsRead() {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NA_IS_READ, 1)
        }
        db.update(TABLE_NEWS_ARTICLES, values, null, null)
    }

    fun getUnreadNewsCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_NEWS_ARTICLES WHERE $COL_NA_IS_READ = 0",
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}

