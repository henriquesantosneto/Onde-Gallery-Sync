package com.onegallery.sync.ui.places

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import com.onegallery.sync.data.local.MediaAccessPermissions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.geometry.LatLng

internal fun lastKnownDeviceLatLng(context: Context): LatLng? {
    if (!MediaAccessPermissions.hasDeviceLocationAccess(context)) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }
    var best: Location? = null
    for (provider in providers) {
        val location = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
        val previous = best
        if (previous == null || location.time > previous.time) {
            best = location
        }
    }
    return best?.let { LatLng(it.latitude, it.longitude) }
}

/** Posição atual do aparelho (sem MapLibre LocationComponent — evita crash com estilo satélite custom). */
@SuppressLint("MissingPermission")
internal suspend fun fetchDeviceLatLng(context: Context): LatLng? {
    if (!MediaAccessPermissions.hasDeviceLocationAccess(context)) return null
    lastKnownDeviceLatLng(context)?.let { return it }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return null
    }
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val provider = when {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        else -> null
    } ?: return null
    return suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
            if (cont.isActive) {
                cont.resume(location?.let { LatLng(it.latitude, it.longitude) })
            }
        }
    }
}
