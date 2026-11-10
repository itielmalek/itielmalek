package com.itiel.smoothgps

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow

/** מצב משותף בין ה-Activity לשירות. */
object MockState {
    /** המיקום המדומה הנוכחי (הנקודה הכחולה). */
    val current = MutableStateFlow<LatLng?>(null)
    /** היעד – הסיכה שגוררים. */
    val target = MutableStateFlow<LatLng?>(null)
    val speedKmh = MutableStateFlow(5f)
    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
}
