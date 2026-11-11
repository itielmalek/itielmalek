package com.itiel.smoothgps

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** ניווט לפי דרכים/שבילים אמיתיים דרך שרת הניתוב החופשי של OpenStreetMap (FOSSGIS). */
object Router {
    /** מחזיר את נקודות המסלול, או null אם נכשל (אין אינטרנט / אין דרך). */
    fun route(from: LatLng, to: LatLng, speedKmh: Float): List<LatLng>? = try {
        val profile = when {
            speedKmh <= 8f -> "routed-foot"   // הליכה – מדרכות ושבילים
            speedKmh <= 25f -> "routed-bike"  // אופניים
            else -> "routed-car"              // רכב
        }
        val url = URL(
            "https://routing.openstreetmap.de/$profile/route/v1/driving/" +
                "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
                "?overview=full&geometries=geojson"
        )
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "SmoothGps/1.4 (Android)")
        c.connectTimeout = 10000
        c.readTimeout = 15000
        val body = c.inputStream.bufferedReader().readText()
        val coords = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            .getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<LatLng>(coords.length() + 2)
        pts.add(from)
        for (i in 0 until coords.length()) {
            val c2 = coords.getJSONArray(i)
            pts.add(LatLng(c2.getDouble(1), c2.getDouble(0)))
        }
        pts.add(to)
        pts
    } catch (_: Exception) { null }
}
