package com.onegallery.sync.data.local

import com.onegallery.sync.ui.gallery.GalleryDisplayItem

/**
 * Partes de endereço (ordem do Geocoder / [MediaLocationResolver.formatAddress]).
 */
data class PlaceComponents(
    val country: String?,
    val adminArea: String?,
    val locality: String?,
    val subLocality: String?,
) {
    fun hasAnyNamedLevel(): Boolean {
        return listOf(country, adminArea, locality, subLocality).any { !it.isNullOrBlank() }
    }

    fun countryKey(): String = country?.normalizedPlaceKey() ?: UNKNOWN_KEY
    fun adminAreaKey(): String = adminArea?.normalizedPlaceKey() ?: ""
    fun localityKey(): String = locality?.normalizedPlaceKey() ?: ""
    fun subLocalityKey(): String = subLocality?.normalizedPlaceKey() ?: ""

    /** Título curto do álbum na folha da árvore (bairro ou cidade). */
    fun leafAlbumTitle(): String {
        return subLocality?.trim().orEmpty().ifBlank {
            locality?.trim().orEmpty().ifBlank {
                adminArea?.trim().orEmpty().ifBlank {
                    country?.trim().orEmpty().ifBlank { "Local" }
                }
            }
        }
    }

    /** Nome único para o bloco, do país ao bairro (ordem fixa para agrupar e ordenar). */
    fun hierarchicalDisplayTitle(): String {
        return listOfNotNull(
            country?.trim()?.takeIf { it.isNotEmpty() },
            adminArea?.trim()?.takeIf { it.isNotEmpty() },
            locality?.trim()?.takeIf { it.isNotEmpty() },
            subLocality?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
    }

    fun hierarchicalSortKey(): List<String> = listOf(
        countryKey(),
        adminAreaKey(),
        localityKey(),
        subLocalityKey(),
    )

    /** Subtítulo opcional (cidade, estado). */
    fun leafAlbumSubtitle(): String? {
        val parts = linkedSetOf<String>()
        if (!subLocality.isNullOrBlank()) {
            locality?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        if (!locality.isNullOrBlank() || !subLocality.isNullOrBlank()) {
            adminArea?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(", ")
    }

    companion object {
        private const val UNKNOWN_KEY = "__unknown__"

        fun fromAddress(
            country: String?,
            adminArea: String?,
            locality: String?,
            subLocality: String?,
            subAdminArea: String? = null,
        ): PlaceComponents {
            val city = locality?.trim().orEmpty().ifBlank { subAdminArea?.trim() }
            return PlaceComponents(
                country = country?.trim()?.takeIf { it.isNotEmpty() },
                adminArea = adminArea?.trim()?.takeIf { it.isNotEmpty() },
                locality = city?.takeIf { it.isNotEmpty() },
                subLocality = subLocality?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        fun forItem(item: GalleryDisplayItem): PlaceComponents? {
            val label = item.locationLabel?.trim().orEmpty()
            if (label.isEmpty()) return null
            return fromGeocoderLabel(label)
        }

        /** Etiqueta gerada por [MediaLocationResolver]: bairro, cidade, subAdmin?, estado, país. */
        fun fromGeocoderLabel(label: String): PlaceComponents? {
            if (label.contains('°')) return null
            val parts = label.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            return when (parts.size) {
                1 -> PlaceComponents(country = parts[0], adminArea = null, locality = null, subLocality = null)
                2 -> PlaceComponents(
                    country = parts[1],
                    adminArea = parts[0],
                    locality = null,
                    subLocality = null,
                )
                3 -> PlaceComponents(
                    country = parts[2],
                    adminArea = parts[1],
                    locality = parts[0],
                    subLocality = null,
                )
                4 -> PlaceComponents(
                    country = parts[3],
                    adminArea = parts[2],
                    locality = parts[1],
                    subLocality = parts[0],
                )
                else -> PlaceComponents(
                    country = parts.last(),
                    adminArea = parts[parts.size - 2],
                    locality = parts[1],
                    subLocality = parts[0],
                )
            }
        }
    }
}

internal fun String.normalizedPlaceKey(): String =
    trim().lowercase().replace(Regex("\\s+"), " ")
