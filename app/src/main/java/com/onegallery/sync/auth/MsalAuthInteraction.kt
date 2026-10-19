package com.onegallery.sync.auth

import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException

/** Quando o MSAL não consegue renovar o token sem mostrar login. */
object MsalAuthInteraction {
    fun needsInteractiveSignIn(error: Throwable?): Boolean {
        if (error == null) return false
        if (error is MsalUiRequiredException) return true
        if (error is MsalException && error.errorCode == "ui_required") return true
        val msg = error.message?.lowercase().orEmpty()
        return msg.contains("interaction_required") ||
            msg.contains("ui_required") ||
            msg.contains("invalid_grant") ||
            msg.contains("no refresh token") ||
            msg.contains("nenhuma conta microsoft") ||
            msg.contains("msal não inicializado")
    }
}
