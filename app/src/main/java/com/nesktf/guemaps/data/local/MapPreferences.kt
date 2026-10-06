package com.nesktf.guemaps.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nesktf.guemaps.data.model.BusPreset
import com.nesktf.guemaps.data.model.FlatBusLine
import org.json.JSONArray
import org.json.JSONObject

class MapPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "guemaps_prefs"
        private const val KEY_LAST_LINE_CODE = "last_line_code"
        private const val KEY_LAST_LINE_DESC = "last_line_desc"
        private const val KEY_LAST_LINE_PATH = "last_line_path"
        private const val KEY_SELECTED_LINES_JSON = "selected_lines_json"
        private const val KEY_PRESETS_JSON = "bus_presets_json"
        private const val MAX_SAVED_LINES = 5
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadPresets(): List<BusPreset> {
        val json = prefs.getString(KEY_PRESETS_JSON, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<BusPreset>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePresets(presets: List<BusPreset>) {
        val json = gson.toJson(presets)
        prefs.edit().putString(KEY_PRESETS_JSON, json).apply()
    }

    fun getSavedSelectedLines(): List<FlatBusLine> {
        val jsonStr = prefs.getString(KEY_SELECTED_LINES_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<FlatBusLine>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        FlatBusLine(
                            groupPath = obj.optString("groupPath", ""),
                            codLinea = obj.getString("codLinea"),
                            descripcion = obj.optString("descripcion", "")
                        )
                    )
                }
                if (list.isNotEmpty()) return list.take(MAX_SAVED_LINES)
            } catch (_: Exception) {}
        }
        val single = getSavedSelectedLine()
        return if (single != null) listOf(single) else emptyList()
    }

    fun saveSelectedLines(lines: List<FlatBusLine>) {
        val array = JSONArray()
        lines.forEach { line ->
            val obj = JSONObject().apply {
                put("codLinea", line.codLinea)
                put("descripcion", line.descripcion)
                put("groupPath", line.groupPath)
            }
            array.put(obj)
        }
        prefs.edit()
            .putString(KEY_SELECTED_LINES_JSON, array.toString())
            .putString(KEY_LAST_LINE_CODE, lines.firstOrNull()?.codLinea)
            .putString(KEY_LAST_LINE_DESC, lines.firstOrNull()?.descripcion)
            .putString(KEY_LAST_LINE_PATH, lines.firstOrNull()?.groupPath)
            .apply()
    }

    private fun getSavedSelectedLine(): FlatBusLine? {
        val code = prefs.getString(KEY_LAST_LINE_CODE, null) ?: return null
        val desc = prefs.getString(KEY_LAST_LINE_DESC, "") ?: ""
        val path = prefs.getString(KEY_LAST_LINE_PATH, "") ?: ""
        return FlatBusLine(groupPath = path, codLinea = code, descripcion = desc)
    }
}
