package com.onegallery.sync.data.local

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MediaLocationResolver(context: Context) {
    private val appContext = context.applicationContext
    private val geocodeCache = LruCache<String, String>(512)

    fun resolveFromUri(uri: Uri): MediaLocation? {
        if (!MediaAccessPermissions.hasMediaLocationAccess(appContext)) return null
        readGpsFromMediaStore(uri)?.let { (lat, lon) ->
            return coordinatesToLocation(lat, lon)
        }
        val coordinates = readGpsCoordinates(uri) ?: return null
        return coordinatesToLocation(coordinates.first, coordinates.second)
    }

    fun resolveFromFile(file: File): MediaLocation? {
        if (!file.isFile) return null
        val coordinates = readGpsFromExif(ExifInterface(file.absolutePath)) ?: return null
        return coordinatesToLocation(coordinates.first, coordinates.second)
    }

    private fun coordinatesToLocation(latitude: Double, longitude: Double): MediaLocation {
        val label = resolveLabel(latitude, longitude)
        return MediaLocation(
            latitude = latitude,
            longitude = longitude,
            label = label,
        )
    }

    private fun readGpsCoordinates(uri: Uri): Pair<Double, Double>? {
        readGpsFromFileDescriptor(uri)?.let { return it }
        readGpsFromInputStream(uri)?.let { return it }
        return null
    }

    /** Preferível: EXIF completo (GPS) sem exigir mark/reset no stream. */
    /** Coordenadas indexadas pelo MediaStore (requer ACCESS_MEDIA_LOCATION). */
    private fun readGpsFromMediaStore(uri: Uri): Pair<Double, Double>? {
        if (uri.scheme != "content") return null
        val projection = arrayOf(
            MediaStore.Images.ImageColumns.LATITUDE,
            MediaStore.Images.ImageColumns.LONGITUDE,
        )
        return runCatching {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val latCol = cursor.getColumnIndex(MediaStore.Images.ImageColumns.LATITUDE)
                val lonCol = cursor.getColumnIndex(MediaStore.Images.ImageColumns.LONGITUDE)
                if (latCol < 0 || lonCol < 0) return@use null
                val lat = cursor.getDouble(latCol)
                val lon = cursor.getDouble(lonCol)
                normalizeCoordinates(lat, lon)
            }
        }.getOrNull()
    }

    private fun readGpsFromFileDescriptor(uri: Uri): Pair<Double, Double>? {
        if (!MediaAccessPermissions.hasMediaLocationAccess(appContext)) return null
        return runCatching {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ExifInterface(pfd.fileDescriptor)
                } else {
                    return@runCatching null
                }
                readGpsFromExif(exif)
            }
        }.getOrNull()
    }

    private fun readGpsFromInputStream(uri: Uri): Pair<Double, Double>? {
        if (!MediaAccessPermissions.hasMediaLocationAccess(appContext)) return null
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                readGpsFromExif(ExifInterface(input))
            }
        }.getOrNull()
    }

    private fun readGpsFromExif(exif: ExifInterface): Pair<Double, Double>? {
        val latLong = FloatArray(2)
        if (!exif.getLatLong(latLong)) return null
        return normalizeCoordinates(latLong[0].toDouble(), latLong[1].toDouble())
    }

    private fun normalizeCoordinates(lat: Double, lon: Double): Pair<Double, Double>? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat == 0.0 && lon == 0.0) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private fun resolveLabel(latitude: Double, longitude: Double): String {
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
