package com.onegallery.sync.ui.places

import com.onegallery.sync.ui.gallery.GalleryDisplayItem

/** Assinatura estável dos pins — só muda quando entram/saem locais ou coordenadas mudam. */
fun buildPhotoMapPinSignature(items: List<GalleryDisplayItem>): String {
    return buildPhotoMapPins(items)
        .asSequence()
        .map { pin ->
            "${pin.item.canonicalPath}\t${pin.latitude}\t${pin.longitude}"
        }
        .sorted()
        .joinToString("\n")
}
