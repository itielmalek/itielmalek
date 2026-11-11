package com.itiel.smoothgps

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var pin: Marker   // היעד – גוררים אותו
    private lateinit var me: Marker    // המיקום המדומה – זז בהדרגה
    private var meAnim: ValueAnimator? = null
    private lateinit var status: TextView
    private lateinit var speedLabel: TextView
    private lateinit var startBtn: Button
    private lateinit var searchBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        speedLabel = findViewById(R.id.speedLabel)
        startBtn = findViewById(R.id.btnStart)
        searchBox = findViewById(R.id.searchBox)
        val bar = findViewById<SeekBar>(R.id.speedBar)

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
        startBtn.setOnClickListener { toggle() }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { search() }
        searchBox.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }

        setupMap()

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MockState.running.collect { r ->
                    startBtn.setText(if (r) R.string.stop else R.string.start)
                    status.setText(if (r) R.string.hint_running else R.string.hint_idle)
                    me.isEnabled = r
                    map.invalidate()
                } }
                launch { MockState.current.collect { moveMe(it) } }
                launch { MockState.error.collect { e ->
                    if (e != null) Toast.makeText(this@MainActivity, e, Toast.LENGTH_LONG).show()
                } }
            }
        }
    }

    private fun setupMap() {
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.minZoomLevel = 3.0
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

        val start = MockState.target.value?.let { GeoPoint(it.latitude, it.longitude) }
            ?: GeoPoint(32.3215, 34.8532) // נתניה כברירת מחדל
        map.controller.setZoom(16.0)
        map.controller.setCenter(start)

        // לחיצה על המפה מציבה את הסיכה
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { setTarget(p); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))

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
                // היעד מתעדכן בזמן אמת תוך כדי הגרירה
                override fun onMarkerDrag(marker: Marker) { MockState.target.value = marker.position.toLL() }
                override fun onMarkerDragEnd(marker: Marker) { MockState.target.value = marker.position.toLL() }
            })
        }
        map.overlays.add(me)
        map.overlays.add(pin)
        MockState.target.value = start.toLL()
    }

    private fun GeoPoint.toLL() = LatLng(latitude, longitude)

    private fun setTarget(p: GeoPoint) {
        pin.position = p
        MockState.target.value = p.toLL()
        map.invalidate()
    }

    private fun search() {
        val q = searchBox.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBox.windowToken, 0)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { geocode(q) }
            if (res == null) {
                Toast.makeText(this@MainActivity, R.string.not_found, Toast.LENGTH_SHORT).show()
            } else {
                setTarget(res)
                map.controller.setZoom(16.0)
                map.controller.animateTo(res)
            }
        }
    }

    /** חיפוש מקום דרך Nominatim של OpenStreetMap (חינם, בלי מפתח). */
    private fun geocode(q: String): GeoPoint? = try {
        val url = URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(q, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "SmoothGps/1.3")
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val arr = JSONArray(c.inputStream.bufferedReader().readText())
        if (arr.length() == 0) null
        else arr.getJSONObject(0).let { GeoPoint(it.getString("lat").toDouble(), it.getString("lon").toDouble()) }
    } catch (_: Exception) { null }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (!MockState.running.value) {
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

    private fun toggle() {
        if (!MockState.running.value && !isMockAppEnabled()) {
            showSetupDialog()
            return
        }
        if (MockState.running.value) {
            startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
        } else {
            MockState.target.value = pin.position.toLL()
            ContextCompat.startForegroundService(this, Intent(this, MockLocationService::class.java))
        }
    }

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
