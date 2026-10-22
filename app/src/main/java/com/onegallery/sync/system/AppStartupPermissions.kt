package com.onegallery.sync.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.onegallery.sync.data.local.MediaAccessPermissions

/** Permissões pedidas uma vez ao abrir o app (runtime). */
object AppStartupPermissions {

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasMediaReadAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES) &&
                hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasNotificationAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Só as que ainda faltam — evita relançar diálogo desnecessário. */
    fun missingAtStartup(context: Context): Array<String> {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES)) {
                missing.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (!hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO)) {
                missing.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (!hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (!hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                missing.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        MediaAccessPermissions.mediaLocationPermission()?.let { permission ->
            if (!hasPermission(context, permission)) {
                missing.add(permission)
            }
        }
        return missing.toTypedArray()
    }

    fun missingMediaRead(context: Context): Array<String> {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES)) {
                missing.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (!hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO)) {
                missing.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else if (!hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            missing.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return missing.toTypedArray()
    }

    fun missingMediaLocation(context: Context): Array<String> {
        val permission = MediaAccessPermissions.mediaLocationPermission() ?: return emptyArray()
        return if (hasPermission(context, permission)) emptyArray() else arrayOf(permission)
    }

    fun missingNotifications(context: Context): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyArray()
        return if (hasNotificationAccess(context)) {
            emptyArray()
        } else {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun missingDeviceLocation(context: Context): Array<String> =
        if (MediaAccessPermissions.hasDeviceLocationAccess(context)) {
            emptyArray()
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
