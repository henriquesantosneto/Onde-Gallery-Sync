package com.onegallery.sync.ui.gallery

import androidx.compose.ui.Modifier

/**
 * Desativa o swipe do pager assim que um segundo dedo encosta, antes da pinça ser reconhecida.
 */
fun Modifier.notifyPagerWhenMultiTouch(onZoomActiveChanged: (Boolean) -> Unit): Modifier =
    observeMultiTouchPinch { active ->
        if (active) {
            onZoomActiveChanged(true)
        }
    }
