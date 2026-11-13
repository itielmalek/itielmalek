package com.itiel.smoothgps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var pin: Marker   // היעד – גוררים אותו
    private lateinit var me: Marker    // המיקום המדומה – זז בהדרגה
    private val routeLine = Polyline()
    private var meAnim: ValueAnimator? = null
    private var routeJob: Job? = null
    private lateinit var status: TextView
    private lateinit var speedLabel: TextView
    private lateinit var startBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var searchBox: EditText
    private lateinit var chkRoute: CheckBox
    private var tilesOk = 0
    private var tilesFail = 0
    private var pendingAction: (() -> Unit)? = null
    private lateinit var chkSteps: CheckBox

    private val stepsPerm = HealthPermission.getWritePermission(StepsRecord::class)

    private val healthLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (stepsPerm !in granted) {
            Toast.makeText(this, R.string.hc_denied, Toast.LENGTH_LONG).show()
            chkSteps.isChecked = false
        }
    }

    /** בקשת הרשאת צעדים מ-Health Connect (מתקין/מפנה לחנות אם חסר). */
    private fun ensureHealthPermission() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            Toast.makeText(this, R.string.hc_missing, Toast.LENGTH_LONG).show()
            chkSteps.isChecked = false
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
            } catch (_: Exception) {}
            return
        }
        lifecycleScope.launch {
            val granted = HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
            if (stepsPerm !in granted) healthLauncher.launch(setOf(stepsPerm))
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingAction
        pendingAction = null
        if (action != null) {
            if (hasLocationPerm()) action()
            else Toast.makeText(this, "נדרשת הרשאת מיקום כדי להפעיל את האפליקציה", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasLocationPerm() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** מוודא הגדרת מיקום מדומה + הרשאות (חובה באנדרואיד 14 לשירות מיקום), ואז מריץ את הפעולה. */
    private fun withReady(action: () -> Unit) {
        if (!isMockAppEnabled()) { showSetupDialog(); return }
        if (hasLocationPerm()) { action(); return }
        pendingAction = action
        permLauncher.launch(requiredPermissions())
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        return perms.toTypedArray()
    }

    /** אם האפליקציה קרסה – שומרים את השגיאה ומציגים אותה בפתיחה הבאה. */
    private fun installCrashReporter() {
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { prefs.edit().putString("last", android.util.Log.getStackTraceString(e)).commit() } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun showLastCrash() {
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val crash = prefs.getString("last", null) ?: return
        prefs.edit().remove("last").apply()
        AlertDialog.Builder(this)
            .setTitle("האפליקציה קרסה בפעם הקודמת")
            .setMessage(crash.take(1800))
            .setPositiveButton("העתק") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("crash", crash))
                Toast.makeText(this, "הועתק – אפשר להדביק ולשלוח לי", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        val osm = Configuration.getInstance()
        osm.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        osm.userAgentValue = "SmoothGps/1.4 ($packageName)"
        val base = File(cacheDir, "osmdroid").apply { mkdirs() }
        osm.osmdroidBasePath = base
        osm.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }

        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        speedLabel = findViewById(R.id.speedLabel)
        startBtn = findViewById(R.id.btnStart)
        lockBtn = findViewById(R.id.btnLock)
        searchBox = findViewById(R.id.searchBox)
        chkRoute = findViewById(R.id.chkRoute)
        val bar = findViewById<SeekBar>(R.id.speedBar)

        // דימוי צעדים
        chkSteps = findViewById(R.id.chkSteps)
        val perKm = findViewById<EditText>(R.id.stepsPerKm)
        val prefs = getSharedPreferences("smoothgps", MODE_PRIVATE)
        MockState.stepsPerKm.value = prefs.getInt("stepsPerKmV2", 1254)
        perKm.setText(MockState.stepsPerKm.value.toString())
        perKm.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = (s?.toString()?.toIntOrNull() ?: return).coerceIn(100, 5000)
                MockState.stepsPerKm.value = v
                prefs.edit().putInt("stepsPerKmV2", v).apply()
            }
        })
        chkSteps.setOnCheckedChangeListener { _, on ->
            MockState.stepsEnabled.value = on
            if (on) ensureHealthPermission()
        }
        findViewById<TextView>(R.id.tvVersion).text = "Smooth GPS v" + try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "?" }

        fun setSpeed(kmh: Int) {
            val v = kmh.coerceIn(1, 120)
            MockState.speedKmh.value = v.toFloat()
            speedLabel.text = "מהירות: $v קמ\"ש"
            bar.progress = v - 1
        }
        setSpeed(5)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) setSpeed(p + 1) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<Button>(R.id.btnWalk).setOnClickListener { setSpeed(5) }
        findViewById<Button>(R.id.btnRun).setOnClickListener { setSpeed(10) }
        findViewById<Button>(R.id.btnBike).setOnClickListener { setSpeed(20) }
        findViewById<Button>(R.id.btnCar).setOnClickListener { setSpeed(50) }
        findViewById<Button>(R.id.btnDev).setOnClickListener { showSetupDialog() }
        findViewById<Button>(R.id.btnFav).setOnClickListener { showFavorites() }
        findViewById<Button>(R.id.btnJump).setOnClickListener { jumpHere() }
        findViewById<Button>(R.id.btnReal).setOnClickListener { resetToRealLocation() }
        lockBtn.setOnClickListener { toggleLock() }
        startBtn.setOnClickListener { toggle() }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { search() }
        searchBox.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }

        setupMap()

        showLastCrash()
        if (!hasLocationPerm()) permLauncher.launch(requiredPermissions())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MockState.running.collect { r ->
                    startBtn.setText(if (r) R.string.stop else R.string.start)
                    if (tilesFail < 8 || tilesOk > 0) status.setText(if (r) R.string.hint_running else R.string.hint_idle)
                    me.isEnabled = r
                    map.invalidate()
                } }
                launch { MockState.locked.collect { l -> lockBtn.setText(if (l) R.string.unlock else R.string.lock) } }
                launch { MockState.current.collect { moveMe(it) } }
                launch { MockState.error.collect { e ->
                    if (e != null) Toast.makeText(this@MainActivity, e, Toast.LENGTH_LONG).show()
                } }
            }
        }
    }

    // ───────────────────────── מפה ─────────────────────────

    private fun setupMap() {
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.minZoomLevel = 3.0
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

        // אם אריחי המפה לא נטענים – מציגים הודעה ברורה במקום מסך אפור
        map.tileProvider.tileRequestCompleteHandlers.add(Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                MapTileProviderBase.MAPTILE_SUCCESS_ID -> tilesOk++
                MapTileProviderBase.MAPTILE_FAIL_ID -> {
                    tilesFail++
                    if (tilesOk == 0 && tilesFail >= 8) status.setText(R.string.map_error)
                }
            }
            true
        })

        val start = MockState.target.value?.let { GeoPoint(it.latitude, it.longitude) }
            ?: GeoPoint(32.3215, 34.8532) // נתניה כברירת מחדל
        map.controller.setZoom(16.0)
        map.controller.setCenter(start)

        // לחיצה על המפה מציבה את הסיכה
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { setTarget(p, true); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))

        routeLine.outlinePaint.color = Color.parseColor("#CC1E88E5")
        routeLine.outlinePaint.strokeWidth = 10f
        map.overlays.add(routeLine)

        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1E88E5"))
            setStroke(5, Color.WHITE)
            setSize(44, 44)
        }
        me = Marker(map).apply {
            position = start
            icon = dot
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> true }
            isEnabled = false
        }
        pin = Marker(map).apply {
            position = start
            isDraggable = true
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { _, _ -> true }
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker) {}
                // בקו אווירי – היעד מתעדכן בזמן אמת תוך כדי הגרירה
                override fun onMarkerDrag(marker: Marker) {
                    MockState.target.value = marker.position.toLL()
                    if (!chkRoute.isChecked) straightTo(marker.position)
                }
                override fun onMarkerDragEnd(marker: Marker) { setTarget(marker.position, false) }
            })
        }
        map.overlays.add(me)
        map.overlays.add(pin)
        MockState.target.value = start.toLL()
    }

    private fun GeoPoint.toLL() = LatLng(latitude, longitude)

    /** מציב את הסיכה ומתחיל ללכת אליה (בדרכים או בקו ישר). */
    private fun setTarget(p: GeoPoint, movePin: Boolean) {
        if (movePin) pin.position = p
        MockState.target.value = p.toLL()
        map.invalidate()
        if (!MockState.running.value) return          // עוד לא התחלנו – הסיכה היא נקודת ההתחלה
        if (MockState.locked.value) {
            Toast.makeText(this, R.string.locked_msg, Toast.LENGTH_SHORT).show()
            return
        }
        if (chkRoute.isChecked) routeTo(p) else straightTo(p)
    }

    private fun straightTo(p: GeoPoint) {
        if (!MockState.running.value || MockState.locked.value) return
        routeJob?.cancel()
        routeLine.setPoints(emptyList())
        MockState.setPath(listOf(p.toLL()))
    }

    private fun routeTo(p: GeoPoint) {
        val from = MockState.current.value ?: return
        val speed = MockState.speedKmh.value
        routeJob?.cancel()
        routeJob = lifecycleScope.launch {
            val pts = withContext(Dispatchers.IO) { Router.route(from, p.toLL(), speed) }
            if (pts == null) {
                Toast.makeText(this@MainActivity, R.string.route_failed, Toast.LENGTH_SHORT).show()
                MockState.setPath(listOf(p.toLL()))
                routeLine.setPoints(emptyList())
            } else {
                MockState.setPath(pts)
                routeLine.setPoints(pts.map { GeoPoint(it.latitude, it.longitude) })
            }
            map.invalidate()
        }
    }

    // ───────────────────────── חיפוש ─────────────────────────

    private fun search() {
        val q = searchBox.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBox.windowToken, 0)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { geocode(q) }
            if (res == null) {
                Toast.makeText(this@MainActivity, R.string.not_found, Toast.LENGTH_SHORT).show()
            } else {
                goTo(res)
            }
        }
    }

    /** מציב את הסיכה במקום ומרכז את המפה עליו. */
    private fun goTo(p: GeoPoint) {
        setTarget(p, true)
        map.controller.setZoom(16.0)
        map.controller.animateTo(p)
    }

    /** חיפוש מקום דרך Nominatim של OpenStreetMap (חינם, בלי מפתח). */
    private fun geocode(q: String): GeoPoint? = try {
        val url = URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(q, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "SmoothGps/1.4 (Android)")
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val arr = JSONArray(c.inputStream.bufferedReader().readText())
        if (arr.length() == 0) null
        else arr.getJSONObject(0).let { GeoPoint(it.getString("lat").toDouble(), it.getString("lon").toDouble()) }
    } catch (_: Exception) { null }

    // ───────────────────────── מועדפים ─────────────────────────

    private fun showFavorites() {
        val favs = Favorites.load(this)
        val b = AlertDialog.Builder(this).setTitle(R.string.fav_title)
            .setNeutralButton(R.string.fav_save) { _, _ -> askFavName() }
            .setNegativeButton(R.string.close, null)
        if (favs.isEmpty()) b.setMessage(R.string.fav_empty)
        else b.setItems(favs.map { it.name }.toTypedArray()) { _, i ->
            goTo(GeoPoint(favs[i].lat, favs[i].lon))
        }
        val d = b.show()
        // לחיצה ארוכה על מועדף = מחיקה
        d.listView?.setOnItemLongClickListener { _, _, i, _ ->
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.fav_delete, favs[i].name))
                .setPositiveButton(R.string.delete) { _, _ ->
                    favs.removeAt(i); Favorites.save(this, favs); d.dismiss(); showFavorites()
                }
                .setNegativeButton(R.string.cancel, null).show()
            true
        }
    }

    private fun askFavName() {
        val input = EditText(this).apply {
            hint = getString(R.string.fav_name_hint)
            setText(searchBox.text.toString().trim())
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle(R.string.fav_save).setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val favs = Favorites.load(this)
                val name = input.text.toString().trim().ifEmpty { "מקום ${favs.size + 1}" }
                favs.add(Fav(name, pin.position.latitude, pin.position.longitude))
                Favorites.save(this, favs)
                Toast.makeText(this, "נשמר: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ───────────────────────── קפיצה / נעילה / התחלה ─────────────────────────

    /** קפיצה מיידית לסיכה (בלי הליכה). אם השירות לא רץ – מפעיל אותו שם. */
    private fun jumpHere() = withReady {
        val p = pin.position.toLL()
        routeLine.setPoints(emptyList())
        if (MockState.running.value) {
            MockState.setPath(emptyList())
            MockState.teleportTo.value = p
        } else {
            startService()
        }
        map.invalidate()
    }

    /** מיקום קבוע: קופץ לסיכה ונשאר שם. לחיצה נוספת משחררת. */
    private fun toggleLock() {
        if (MockState.locked.value) { MockState.locked.value = false; return }
        withReady { lockHere() }
    }

    private fun lockHere() {
        MockState.locked.value = true
        routeLine.setPoints(emptyList())
        if (MockState.running.value) {
            MockState.setPath(emptyList())
            MockState.teleportTo.value = pin.position.toLL()
        } else {
            startService()
        }
    }

    // ───────────────────────── חזרה למיקום האמיתי ─────────────────────────

    /** עוצר את הדימוי (המערכת חוזרת ל-GPS האמיתי), ממקם את הסיכה והמפה במיקום האמיתי. */
    private fun resetToRealLocation() {
        if (!hasLocationPerm()) {
            pendingAction = { resetToRealLocation() }
            permLauncher.launch(requiredPermissions())
            return
        }
        Toast.makeText(this, R.string.real_loc_wait, Toast.LENGTH_SHORT).show()
        routeJob?.cancel()
        routeLine.setPoints(emptyList())
        MockState.locked.value = false
        lifecycleScope.launch {
            if (MockState.running.value) {
                stopService(Intent(this@MainActivity, MockLocationService::class.java))
                withTimeoutOrNull(3000) { while (MockState.running.value) delay(100) }
                delay(800) // זמן למערכת לחזור לספקי המיקום האמיתיים
            }
            val real = realLocation()
            if (real == null) {
                Toast.makeText(this@MainActivity, R.string.real_loc_fail, Toast.LENGTH_LONG).show()
            } else {
                goTo(real)
            }
        }
    }

    /** מיקום אמיתי חד-פעמי מהרשת או מה-GPS (בלי מיקום מדומה). */
    @SuppressLint("MissingPermission")
    private suspend fun realLocation(): GeoPoint? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (prov in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            if (!lm.isProviderEnabled(prov)) continue
            val loc = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        val cs = CancellationSignal()
                        cont.invokeOnCancellation { cs.cancel() }
                        lm.getCurrentLocation(prov, cs, ContextCompat.getMainExecutor(this)) { l ->
                            if (cont.isActive) cont.resume(l)
                        }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(l: Location) {
                                lm.removeUpdates(this)
                                if (cont.isActive) cont.resume(l)
                            }
                        }
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(prov, listener, Looper.getMainLooper())
                        cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    }
                }
            }
            if (loc != null) return GeoPoint(loc.latitude, loc.longitude)
        }
        return null
    }

    private fun toggle() {
        if (MockState.running.value) {
            stopService(Intent(this, MockLocationService::class.java))
            MockState.locked.value = false
            routeLine.setPoints(emptyList())
            map.invalidate()
            return
        }
        withReady {
            MockState.locked.value = false
            startService()
        }
    }

    private fun startService() {
        MockState.target.value = pin.position.toLL()
        MockState.setPath(emptyList())
        ContextCompat.startForegroundService(this, Intent(this, MockLocationService::class.java))
    }

    // ───────────────────────── הגדרת מיקום מדומה ─────────────────────────

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (!MockState.running.value && (tilesFail < 8 || tilesOk > 0)) {
            status.setText(if (isMockAppEnabled()) R.string.mock_ok else R.string.mock_missing)
        }
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    private fun isMockAppEnabled(): Boolean = try {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        ops.checkOp("android:mock_location", Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun devOptionsOn(): Boolean =
        Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** מדריך + מעבר ישיר להגדרות המפתחים (או ל"אודות הטלפון" אם הן עוד לא פעילות). */
    private fun showSetupDialog() {
        val devOn = devOptionsOn()
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_title)
            .setMessage(getString(R.string.setup_steps))
            .setPositiveButton(if (devOn) R.string.open_dev else R.string.open_about) { _, _ ->
                val action = if (devOn) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                             else Settings.ACTION_DEVICE_INFO_SETTINGS
                try { startActivity(Intent(action)) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ───────────────────────── אנימציה ─────────────────────────

    /** אנימציה ליניארית של הנקודה הכחולה בין עדכוני השירות (שנייה). */
    private fun moveMe(to: LatLng?) {
        if (to == null || !::me.isInitialized) return
        meAnim?.cancel()
        val from = me.position
        val dest = GeoPoint(to.latitude, to.longitude)
        if (MockLocationService.distance(LatLng(from.latitude, from.longitude), to) > 500) {
            me.position = dest; map.invalidate(); return
        }
        meAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction.toDouble()
                me.position = GeoPoint(
                    from.latitude + (dest.latitude - from.latitude) * t,
                    from.longitude + (dest.longitude - from.longitude) * t
                )
                map.invalidate()
            }
            start()
        }
    }
}
