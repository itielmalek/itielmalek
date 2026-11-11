package com.itiel.smoothgps

import kotlinx.coroutines.flow.MutableStateFlow

data class LatLng(val latitude: Double, val longitude: Double)

/** מסלול שהשירות צריך ללכת בו. id חדש = מסלול חדש (מחליף את הקודם). */
class PathCmd(val id: Long, val points: List<LatLng>)

/** מצב משותף בין ה-Activity לשירות. */
object MockState {
    /** המיקום המדומה הנוכחי (הנקודה הכחולה). */
    val current = MutableStateFlow<LatLng?>(null)
    /** מיקום הסיכה; גם נקודת ההתחלה כשמפעילים את השירות. */
    val target = MutableStateFlow<LatLng?>(null)
    /** הנקודות שהמיקום ילך דרכן, לפי הסדר. */
    val path = MutableStateFlow(PathCmd(0, emptyList()))
    /** true = המיקום קבוע ולא זז. */
    val locked = MutableStateFlow(false)
    /** קפיצה מיידית למיקום (נצרך פעם אחת ע"י השירות). */
    val teleportTo = MutableStateFlow<LatLng?>(null)
    val speedKmh = MutableStateFlow(5f)
    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun setPath(points: List<LatLng>) {
        path.value = PathCmd(System.nanoTime(), points)
    }
}
