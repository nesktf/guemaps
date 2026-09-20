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
import com.nesktf.guemaps.data.model.SavedCard
import com.nesktf.guemaps.data.model.computeRouteHash

class GuemapsDatabase(
    context: Context,
    private val gson: Gson = Gson()
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "guemaps.db"
        const val DATABASE_VERSION = 4

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
}
