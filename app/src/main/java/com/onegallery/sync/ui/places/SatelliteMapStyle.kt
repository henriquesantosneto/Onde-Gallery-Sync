package com.onegallery.sync.ui.places

/** Estilo MapLibre com tiles Esri World Imagery (visão satélite). */
object SatelliteMapStyle {
    const val ATTRIBUTION = "© Esri, Maxar, Earthstar Geographics"

    fun json(): String = """
        {
          "version": 8,
          "name": "OneGallery Satellite",
          "sources": {
            "satellite": {
              "type": "raster",
              "tiles": [
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
              ],
              "tileSize": 256,
              "maxzoom": 19,
              "attribution": "$ATTRIBUTION"
            }
          },
          "layers": [
            {
              "id": "satellite",
              "type": "raster",
              "source": "satellite"
            }
          ]
        }
    """.trimIndent()
}
