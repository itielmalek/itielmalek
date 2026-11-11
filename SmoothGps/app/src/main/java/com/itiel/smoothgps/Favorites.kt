package com.itiel.smoothgps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Fav(val name: String, val lat: Double, val lon: Double)

object Favorites {
    private const val PREFS = "smoothgps"
    private const val KEY = "favs"

    fun load(ctx: Context): MutableList<Fav> {
        val out = mutableListOf<Fav>()
        try {
            val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Fav(o.getString("n"), o.getDouble("lat"), o.getDouble("lon")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, list: List<Fav>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("lat", it.lat).put("lon", it.lon)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
