package com.onegallery.sync.util

import android.util.Log
import com.onegallery.sync.BuildConfig

object GalleryPerfLogger {
    private const val TAG = "OneGalleryPerf"

    @Volatile
    var enabled: Boolean = false

    fun log(message: String) {
        if (BuildConfig.DEBUG || enabled) {
            Log.i(TAG, message)
        }
    }
}
