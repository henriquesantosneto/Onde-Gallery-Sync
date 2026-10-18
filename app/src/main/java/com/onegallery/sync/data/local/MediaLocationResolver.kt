package com.onegallery.sync.data.local

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import java.util.Locale
import kotlin.math.roundToInt

class MediaLocationResolver(context: Context) {
    private val appContext = context.applicationContext
    private val geocodeCache = LruCache<String, String>(512)

    fun resolveFromUri(uri: Uri): MediaLocation? {
        val coordinates = readGpsCoordinates(uri) ?: return null
        val label = resolveLabel(coordinates.first, coordinates.second) ?: return null
        return MediaLocation(
            latitude = coordinates.first,
            longitude = coordinates.second,
            label = label,
        )
    }

    private fun readGpsCoordinates(uri: Uri): Pair<Double, Double>? {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val latLong = FloatArray(2)
                if (!exif.getLatLong(latLong)) return@runCatching null
                latLong[0].toDouble() to latLong[1].toDouble()
            }
        }.getOrNull()
    }

    private fun resolveLabel(latitude: Double, longitude: Double): String? {
        val cacheKey = "${roundCoord(latitude)},${roundCoord(longitude)}"
        geocodeCache.get(cacheKey)?.let { return it }
        val label = if (Geocoder.isPresent()) {
            reverseGeocode(latitude, longitude)
        } else {
            null
        } ?: formatCoordinates(latitude, longitude)
        geocodeCache.put(cacheKey, label)
        return label
    }

    private fun reverseGeocode(latitude: Double, longitude: Double): String? {
        return runCatching {
            val geocoder = Geocoder(appContext, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(latitude, longitude, 1)
            formatAddress(list?.firstOrNull())
        }.getOrNull()
    }

    private fun formatAddress(address: android.location.Address?): String? {
        if (address == null) return null
        val locality = address.locality?.takeIf { it.isNotBlank() }
        val admin = address.subAdminArea?.takeIf { it.isNotBlank() }
        val country = address.countryName?.takeIf { it.isNotBlank() }
        return when {
            locality != null && country != null && locality != country ->
                "$locality, $country"
            locality != null -> locality
            admin != null && country != null -> "$admin, $country"
            admin != null -> admin
            country != null -> country
            else -> null
        }
    }

    private fun formatCoordinates(latitude: Double, longitude: Double): String {
        return "${roundCoord(latitude)}°, ${roundCoord(longitude)}°"
    }

    private fun roundCoord(value: Double): String {
        return ((value * 100.0).roundToInt() / 100.0).toString()
    }
}
