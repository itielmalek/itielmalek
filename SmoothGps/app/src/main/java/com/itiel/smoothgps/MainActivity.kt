package com.itiel.smoothgps

import android.Manifest
import android.app.AppOpsManager
import android.app.AlertDialog
import android.os.Process
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private var map: GoogleMap? = null
    private var pin: Marker? = null      // היעד – גוררים אותו
    private var me: Marker? = null       // המיקום המדומה – זז בהדרגה
    private var meAnim: ValueAnimator? = null
    private lateinit var status: TextView
    private lateinit var speedLabel: TextView
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        speedLabel = findViewById(R.id.speedLabel)
        startBtn = findViewById(R.id.btnStart)
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

        (supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment).getMapAsync(this)

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MockState.running.collect { r ->
                    startBtn.setText(if (r) R.string.stop else R.string.start)
                    status.setText(if (r) R.string.hint_running else R.string.hint_idle)
                    me?.isVisible = r
                } }
                launch { MockState.current.collect { moveMe(it) } }
                launch { MockState.error.collect { e ->
                    if (e != null) Toast.makeText(this@MainActivity, e, Toast.LENGTH_LONG).show()
                } }
            }
        }
    }

    override fun onMapReady(m: GoogleMap) {
        map = m
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            @Suppress("MissingPermission") m.isMyLocationEnabled = false
        }
        val start = MockState.target.value ?: LatLng(32.0853, 34.7818) // תל אביב כברירת מחדל
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 16f))
        pin = m.addMarker(MarkerOptions().position(start).draggable(true).zIndex(2f))
        me = m.addMarker(
            MarkerOptions().position(start).visible(false).anchor(0.5f, 0.5f)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        MockState.target.value = start

        m.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(mk: Marker) {}
            // היעד מתעדכן בזמן אמת תוך כדי הגרירה
            override fun onMarkerDrag(mk: Marker) { MockState.target.value = mk.position }
            override fun onMarkerDragEnd(mk: Marker) { MockState.target.value = mk.position }
        })
        // לחיצה על המפה מזיזה את הסיכה (גם כן בלי קפיצה של המיקום עצמו)
        m.setOnMapClickListener { p -> pin?.position = p; MockState.target.value = p }
    }

    override fun onResume() {
        super.onResume()
        if (!MockState.running.value) {
            status.setText(if (isMockAppEnabled()) R.string.mock_ok else R.string.mock_missing)
        }
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
            MockState.target.value = pin?.position ?: return
            ContextCompat.startForegroundService(this, Intent(this, MockLocationService::class.java))
        }
    }

    /** אנימציה ליניארית של הנקודה הכחולה בין עדכוני השירות (שנייה). */
    private fun moveMe(to: LatLng?) {
        val marker = me ?: return
        if (to == null) return
        meAnim?.cancel()
        val from = marker.position
        if (MockLocationService.distance(from, to) > 500) { marker.position = to; return }
        meAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                marker.position = LatLng(
                    from.latitude + (to.latitude - from.latitude) * t,
                    from.longitude + (to.longitude - from.longitude) * t
                )
            }
            start()
        }
    }
}
