package com.onegallery.sync.data.local

import com.onegallery.sync.ui.gallery.GalleryDisplayItem
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class PlaceLevel {
    COUNTRY,
    ADMIN_AREA,
    LOCALITY,
    SUB_LOCALITY,
    LEGACY_LABEL,
}

data class PlaceMatchKey(
    val level: PlaceLevel,
    val parts: List<String>,
) {
    fun encode(): String {
        val body = parts.joinToString("|") { part ->
            URLEncoder.encode(part, StandardCharsets.UTF_8.name())
        }
        return "${level.name}:$body"
    }

    companion object {
        fun legacyLabel(label: String) = PlaceMatchKey(PlaceLevel.LEGACY_LABEL, listOf(label))

        fun decode(raw: String): PlaceMatchKey? {
            val colon = raw.indexOf(':')
            if (colon <= 0) return null
            val level = runCatching { PlaceLevel.valueOf(raw.substring(0, colon)) }.getOrNull() ?: return null
            val body = raw.substring(colon + 1)
            if (body.isEmpty()) return PlaceMatchKey(level, emptyList())
            val parts = body.split('|').map { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            return PlaceMatchKey(level, parts)
        }

        fun forComponents(components: PlaceComponents, legacyLabel: String?): PlaceMatchKey {
            if (!components.hasAnyNamedLevel()) {
                return legacyLabel?.let { legacyLabel(it) }
                    ?: PlaceMatchKey(PlaceLevel.LEGACY_LABEL, listOf(""))
            }
            return when {
                !components.subLocality.isNullOrBlank() &&
                    !components.locality.isNullOrBlank() &&
                    !components.adminArea.isNullOrBlank() &&
                    !components.country.isNullOrBlank() ->
                    PlaceMatchKey(
                        PlaceLevel.SUB_LOCALITY,
                        listOf(
                            components.country!!,
                            components.adminArea!!,
                            components.locality!!,
                            components.subLocality!!,
                        ),
                    )
                !components.locality.isNullOrBlank() &&
                    !components.adminArea.isNullOrBlank() &&
                    !components.country.isNullOrBlank() ->
                    PlaceMatchKey(
                        PlaceLevel.LOCALITY,
                        listOf(components.country!!, components.adminArea!!, components.locality!!),
                    )
                !components.adminArea.isNullOrBlank() && !components.country.isNullOrBlank() ->
                    PlaceMatchKey(
                        PlaceLevel.ADMIN_AREA,
                        listOf(components.country!!, components.adminArea!!),
                    )
                !components.country.isNullOrBlank() ->
                    PlaceMatchKey(PlaceLevel.COUNTRY, listOf(components.country!!))
                else -> legacyLabel?.let { legacyLabel(it) } ?: legacyLabel("")
            }
        }
    }
}

fun GalleryDisplayItem.matchesPlace(key: PlaceMatchKey): Boolean {
    if (key.level == PlaceLevel.LEGACY_LABEL) {
        val legacy = key.parts.firstOrNull().orEmpty()
        return locationLabel == legacy
    }
    val components = PlaceComponents.forItem(this) ?: return false
    fun eq(a: String?, b: String): Boolean =
        a?.trim()?.equals(b.trim(), ignoreCase = true) == true

    return when (key.level) {
        PlaceLevel.COUNTRY -> eq(components.country, key.parts.getOrElse(0) { "" })
        PlaceLevel.ADMIN_AREA ->
            eq(components.country, key.parts.getOrElse(0) { "" }) &&
                eq(components.adminArea, key.parts.getOrElse(1) { "" })
        PlaceLevel.LOCALITY ->
            eq(components.country, key.parts.getOrElse(0) { "" }) &&
                eq(components.adminArea, key.parts.getOrElse(1) { "" }) &&
                eq(components.locality, key.parts.getOrElse(2) { "" })
        PlaceLevel.SUB_LOCALITY ->
            eq(components.country, key.parts.getOrElse(0) { "" }) &&
                eq(components.adminArea, key.parts.getOrElse(1) { "" }) &&
                eq(components.locality, key.parts.getOrElse(2) { "" }) &&
                eq(components.subLocality, key.parts.getOrElse(3) { "" })
        PlaceLevel.LEGACY_LABEL -> locationLabel == key.parts.firstOrNull()
    }
}

fun PlaceComponents.preferredBrowseKey(legacyLabel: String?): PlaceMatchKey =
    PlaceMatchKey.forComponents(this, legacyLabel)
