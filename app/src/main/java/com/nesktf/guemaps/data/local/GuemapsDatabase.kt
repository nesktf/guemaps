package com.nesktf.guemaps.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.nesktf.guemaps.data.model.BusGroupsResponse
import com.nesktf.guemaps.data.model.BusRouteResponse
import com.nesktf.guemaps.data.model.SavedCard

class GuemapsDatabase(
    context: Context,
    private val gson: Gson = Gson()
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "guemaps.db"
        const val DATABASE_VERSION = 1

        private const val TABLE_BUS_GROUPS = "bus_groups_cache"
        private const val COL_BG_ID = "id"
        private const val COL_BG_JSON = "data_json"
        private const val COL_BG_UPDATED_AT = "updated_at"

        private const val TABLE_BUS_ROUTES = "bus_routes_cache"
        private const val COL_BR_LINE_ID = "line_id"
        private const val COL_BR_JSON = "route_json"
        private const val COL_BR_UPDATED_AT = "updated_at"

        private const val TABLE_RECENT_CARDS = "recent_cards"
        private const val COL_RC_CARD_NUMBER = "card_number"
        private const val COL_RC_ALIAS = "alias"
        private const val COL_RC_LAST_CHECKED = "last_checked"
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
                $COL_BR_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_RECENT_CARDS (
                $COL_RC_CARD_NUMBER TEXT PRIMARY KEY,
                $COL_RC_ALIAS TEXT,
                $COL_RC_LAST_CHECKED INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BUS_GROUPS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BUS_ROUTES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_RECENT_CARDS")
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

    // --- Bus Routes Cache ---

    fun saveBusRoute(lineId: String, routeResponse: BusRouteResponse) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_BR_LINE_ID, lineId)
            put(COL_BR_JSON, gson.toJson(routeResponse))
            put(COL_BR_UPDATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_BUS_ROUTES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
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
}
