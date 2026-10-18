package com.onegallery.sync.ui.places

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.onegallery.sync.data.local.MediaAccessPermissions
import org.maplibre.android.geometry.LatLng

internal fun lastKnownDeviceLatLng(context: Context): LatLng? {
    if (!MediaAccessPermissions.hasDeviceLocationAccess(context)) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
    }
    var best: Location? = null
    for (provider in providers) {
        val location = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
        if (best == null || location.time > best.time) {
            best = location
        }
    }
    return best?.let { LatLng(it.latitude, it.longitude) }
}
