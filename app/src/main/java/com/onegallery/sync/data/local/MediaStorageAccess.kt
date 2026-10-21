package com.onegallery.sync.data.local

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

object MediaStorageAccess {
    /** Android 11+: acesso amplo — remover cópias locais (liberar espaço / excluir) sem diálogo repetido. */
    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /** Abre a tela do sistema para conceder acesso. Preferir [Activity] (sem NEW_TASK). */
    fun openAllFilesAccessSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val activity = context.findActivity()
        val starter = activity ?: context.applicationContext
        val pkg = context.packageName
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$pkg")
            },
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
            },
        )
        for (intent in intents) {
            if (startSettingsScreen(starter, activity == null, intent)) return true
        }
        return false
    }

    private fun startSettingsScreen(context: Context, newTask: Boolean, intent: Intent): Boolean {
        return runCatching {
            if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
