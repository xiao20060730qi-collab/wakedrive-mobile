package com.wakedrive.mobile.speed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * GPS speed driven monitoring rules:
 *  - speed > 0.5 m/s: monitoring auto-starts and cannot be stopped manually
 *  - speed == 0 for 3 minutes: driver is assumed to have stopped to rest, monitoring ends
 */
class SpeedMonitor(
    private val context: Context,
    private val onSpeedChanged: (kmh: Float) -> Unit,
    private val onDriving: () -> Unit,
    private val onStoppedForRest: () -> Unit,
) {
    companion object {
        private const val ACTIVE_SPEED_MPS = 0.5f
        private const val STOP_CONFIRM_MS = 180_000L
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var zeroSince = 0L
    private var listening = false

    @Volatile
    var driving: Boolean = false
        private set

    var lastSpeedKmh: Float = 0f
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!location.hasSpeed()) return
            handleSpeed(location.speed.toDouble())
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private fun handleSpeed(speedMps: Double) {
        lastSpeedKmh = (speedMps * 3.6).toFloat()
        onSpeedChanged(lastSpeedKmh)
        if (speedMps > ACTIVE_SPEED_MPS) {
            driving = true
            zeroSince = 0L
            onDriving()
        } else {
            driving = false
            if (zeroSince == 0L) {
                zeroSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - zeroSince >= STOP_CONFIRM_MS) {
                zeroSince = 0L
                onStoppedForRest()
            }
        }
    }

    /** Engineering-mode hook: feed a synthetic speed sample (m/s). */
    fun simulate(speedMps: Double) = handleSpeed(speedMps)

    fun start() {
        if (listening) return
        val manager = locationManager ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
            listening = true
        } catch (error: SecurityException) {
            listening = false
        }
    }

    fun stop() {
        if (!listening) return
        try {
            locationManager?.removeUpdates(listener)
        } catch (error: Exception) {
            // ignore
        }
        listening = false
        driving = false
        zeroSince = 0L
    }
}
