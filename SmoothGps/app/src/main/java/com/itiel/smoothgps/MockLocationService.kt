package com.itiel.smoothgps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.getAndUpdate
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneId
import kotlin.math.*
import kotlin.random.Random

/**
 * שירות רקע שמזיז את המיקום המדומה צעד-צעד לעבר היעד במהירות שנבחרה,
 * ושולח עדכון כל שנייה (כמו GPS אמיתי) – בלי קפיצות.
 */
class MockLocationService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private lateinit var lm: LocationManager
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(1, buildNotification())
        } catch (e: Exception) {
            MockState.error.value = "לא ניתן להפעיל שירות רקע: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!setupProviders()) {
            MockState.error.value = getString(R.string.err_mock)
            stopSelf()
            return START_NOT_STICKY
        }
        MockState.error.value = null
        MockState.running.value = true
        job?.cancel()
        job = scope.launch { loop() }
        return START_NOT_STICKY
    }

    private fun setupProviders(): Boolean = try {
        for (p in providers) {
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                lm.addTestProvider(
                    p, false, false, false, false, true, true, true,
                    ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                lm.addTestProvider(p, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            }
            lm.setTestProviderEnabled(p, true)
        }
        true
    } catch (e: SecurityException) {
        false
    }

    private suspend fun loop() {
        var pos = MockState.target.value ?: return
        MockState.current.value = pos
        var speedNoise = 0.0
        var bearing = 0f
        val dt = 1.0 // שניות בין עדכונים
        val queue = ArrayDeque<LatLng>()
        var seenPath = MockState.path.value.id
        queue.addAll(MockState.path.value.points)
        var lastTick = SystemClock.elapsedRealtime()
        var stepAcc = 0.0
        var stepStart = System.currentTimeMillis()
        var lastFlush = stepStart

        while (currentCoroutineContext().isActive) {
            // קפיצה מיידית (כפתור "קפוץ לכאן")
            MockState.teleportTo.getAndUpdate { null }?.let { pos = it; queue.clear() }

            // מסלול חדש מה-Activity
            val cmd = MockState.path.value
            if (cmd.id != seenPath) {
                seenPath = cmd.id
                queue.clear()
                queue.addAll(cmd.points)
            }

            var v = 0.0
            var moved = 0.0
            if (!MockState.locked.value && queue.isNotEmpty()) {
                val maxSpeed = MockState.speedKmh.value / 3.6 // מ'/ש'
                // רעש איטי במהירות (±7%) כדי שלא יהיה רובוטי
                speedNoise = (speedNoise * 0.85 + Random.nextDouble(-0.07, 0.07)).coerceIn(-0.07, 0.07)
                v = maxSpeed * (1 + speedNoise)

                // האטה רכה בקרבת היעד הסופי
                if (queue.size == 1) {
                    val brakeDist = v * 2.0
                    val d = distance(pos, queue.first())
                    if (d < brakeDist) v *= (d / brakeDist).coerceIn(0.15, 1.0)
                }

                // מתקדמים לאורך הנקודות; מה שנשאר מהצעד עובר לנקודה הבאה
                var remaining = v * dt
                while (remaining > 0 && queue.isNotEmpty()) {
                    val g = queue.first()
                    val d = distance(pos, g)
                    if (d <= remaining) {
                        if (d > 0.05) bearing = bearing(pos, g)
                        pos = g
                        moved += d
                        remaining -= d
                        queue.removeFirst()
                    } else {
                        bearing = bearing(pos, g)
                        pos = destination(pos, bearing.toDouble(), remaining)
                        moved += remaining
                        remaining = 0.0
                    }
                }
            }
            MockState.current.value = pos
            push(pos, v.toFloat(), bearing)

            // דימוי צעדים: מצטברים לפי המרחק שנעשה, ונכתבים ל-Health Connect כל ~20 שניות
            if (MockState.stepsEnabled.value && moved > 0) {
                stepAcc += moved / 1000.0 * MockState.stepsPerKm.value
            }
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastFlush >= 20_000) {
                lastFlush = nowMs
                val n = stepAcc.toLong()
                if (n > 0 && MockState.stepsEnabled.value) {
                    stepAcc -= n
                    val start = Instant.ofEpochMilli(stepStart)
                    val end = Instant.ofEpochMilli(max(nowMs, stepStart + 1000))
                    stepStart = nowMs
                    stepScope.launch { writeSteps(applicationContext, n, start, end) }
                } else if (stepAcc <= 0.0 || !MockState.stepsEnabled.value) {
                    stepAcc = 0.0
                    stepStart = nowMs
                }
            }

            val now = SystemClock.elapsedRealtime()
            delay(max(0L, (dt * 1000).toLong() - (now - lastTick)))
            lastTick = SystemClock.elapsedRealtime()
        }
    }

    private fun push(p: LatLng, speed: Float, bearing: Float) {
        // סטייה קטנה בדיווח בלבד (לא במיקום הפנימי) – כמו רעש GPS אמיתי
        val jitter = destination(p, Random.nextDouble(0.0, 360.0), Random.nextDouble(0.0, 1.0))
        for (provider in providers) {
            val loc = Location(provider).apply {
                latitude = jitter.latitude
                longitude = jitter.longitude
                altitude = 30.0 + Random.nextDouble(-0.5, 0.5)
                accuracy = Random.nextFloat() * 3f + 3f
                this.speed = speed
                this.bearing = bearing
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    bearingAccuracyDegrees = 5f
                    speedAccuracyMetersPerSecond = 0.5f
                    verticalAccuracyMeters = 4f
                }
            }
            try { lm.setTestProviderLocation(provider, loc) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        if (::lm.isInitialized) for (p in providers) {
            try { lm.setTestProviderEnabled(p, false); lm.removeTestProvider(p) } catch (_: Exception) {}
        }
        MockState.running.value = false
        MockState.locked.value = false
        MockState.setPath(emptyList())
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Smooth GPS", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.hint_running))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        private val stepScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private suspend fun writeSteps(ctx: Context, n: Long, start: Instant, end: Instant) {
            try {
                if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) {
                    MockState.error.value = "Health Connect לא זמין במכשיר"
                    return
                }
                val client = HealthConnectClient.getOrCreate(ctx)
                val perm = HealthPermission.getWritePermission(StepsRecord::class)
                if (perm !in client.permissionController.getGrantedPermissions()) {
                    MockState.error.value = "אין הרשאת צעדים ב-Health Connect – כבה והדלק את \"דמה צעדים\""
                    return
                }
                val rules = ZoneId.systemDefault().rules
                client.insertRecords(listOf(
                    StepsRecord(
                        count = n,
                        startTime = start, startZoneOffset = rules.getOffset(start),
                        endTime = end, endZoneOffset = rules.getOffset(end),
                        // נרשם כצעדים שנמדדו אוטומטית בטלפון (כמו חיישן הצעדים), ולא כהזנה ידנית
                        metadata = Metadata(
                            recordingMethod = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
                            device = Device(
                                manufacturer = android.os.Build.MANUFACTURER,
                                model = android.os.Build.MODEL,
                                type = Device.TYPE_PHONE
                            )
                        )
                    )
                ))
            } catch (e: Exception) {
                MockState.error.value = "שגיאה בכתיבת צעדים: ${e.message}"
            }
        }

        const val ACTION_STOP = "com.itiel.smoothgps.STOP"
        private const val CHANNEL = "smooth_gps"
        private const val R_EARTH = 6371000.0

        fun distance(a: LatLng, b: LatLng): Double {
            val r = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, r)
            return r[0].toDouble()
        }

        fun bearing(a: LatLng, b: LatLng): Float {
            val f1 = Math.toRadians(a.latitude); val f2 = Math.toRadians(b.latitude)
            val dl = Math.toRadians(b.longitude - a.longitude)
            val y = sin(dl) * cos(f2)
            val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
            return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        }

        fun destination(a: LatLng, bearingDeg: Double, meters: Double): LatLng {
            val d = meters / R_EARTH
            val br = Math.toRadians(bearingDeg)
            val f1 = Math.toRadians(a.latitude); val l1 = Math.toRadians(a.longitude)
            val f2 = asin(sin(f1) * cos(d) + cos(f1) * sin(d) * cos(br))
            val l2 = l1 + atan2(sin(br) * sin(d) * cos(f1), cos(d) - sin(f1) * sin(f2))
            return LatLng(Math.toDegrees(f2), ((Math.toDegrees(l2) + 540) % 360) - 180)
        }
    }
}
