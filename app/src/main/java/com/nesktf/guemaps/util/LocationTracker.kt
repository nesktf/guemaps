package com.nesktf.guemaps.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

class LocationTracker(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var locationListener: LocationListener? = null

    fun isPermissionGranted(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return hasFine || hasCoarse
    }

    fun isProviderEnabled(): Boolean {
        return try {
            LocationManagerCompat.isLocationEnabled(locationManager)
        } catch (_: Exception) {
            val enabledProviders = try { locationManager.getProviders(true) } catch (_: Exception) { emptyList() }
            enabledProviders.isNotEmpty()
        }
    }

    fun getLastKnownLocation(): Location? {
        val allProviders = try { locationManager.allProviders } catch (_: Exception) { emptyList() }
        var bestLast: Location? = null
        for (p in allProviders) {
            try {
                val loc = locationManager.getLastKnownLocation(p)
                if (loc != null && (bestLast == null || loc.time > bestLast.time)) {
                    bestLast = loc
                }
            } catch (_: SecurityException) {}
        }
        return bestLast
    }

    fun requestLocationUpdates(
        onLocationChanged: (Location) -> Unit,
        onLocatingChanged: (Boolean) -> Unit
    ) {
        if (!isPermissionGranted() || !isProviderEnabled()) return

        onLocatingChanged(true)

        val bestLast = getLastKnownLocation()
        if (bestLast != null) {
            onLocationChanged(bestLast)
        }

        stopLocationUpdates(onLocatingChanged)

        var isInitialFix = true
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocationChanged(location)
                if (isInitialFix) {
                    isInitialFix = false
                    onLocatingChanged(false)
                }
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
        }
        locationListener = listener

        val enabledProviders = try { locationManager.getProviders(true) } catch (_: Exception) { emptyList() }
        for (p in enabledProviders) {
            try {
                locationManager.requestLocationUpdates(p, 2000L, 2f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {}
        }
    }

    fun stopLocationUpdates(onLocatingChanged: ((Boolean) -> Unit)? = null) {
        onLocatingChanged?.invoke(false)
        locationListener?.let {
            try { locationManager.removeUpdates(it) } catch (_: SecurityException) {}
            locationListener = null
        }
    }
}
