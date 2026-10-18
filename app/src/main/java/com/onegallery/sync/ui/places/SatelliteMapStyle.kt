package com.onegallery.sync.ui.places

/** Satélite Esri + camada de nomes (países, cidades, estradas). */
object SatelliteMapStyle {
    const val ATTRIBUTION = "© Esri — imagens e rótulos cartográficos"

    fun json(): String = """
        {
          "version": 8,
          "name": "OneGallery Satellite Labels",
          "sources": {
            "satellite": {
              "type": "raster",
              "tiles": [
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
              ],
              "tileSize": 256,
              "maxzoom": 19
            },
            "places-labels": {
              "type": "raster",
              "tiles": [
                "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"
              ],
              "tileSize": 256,
              "maxzoom": 19
            }
          },
          "layers": [
            {
              "id": "satellite",
              "type": "raster",
              "source": "satellite"
            },
            {
              "id": "places-labels",
              "type": "raster",
              "source": "places-labels"
            }
          ]
        }
    """.trimIndent()
}
