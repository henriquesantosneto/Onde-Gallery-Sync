package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.onegallery.sync.ui.places.SatelliteMapStyle
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Recorte estático do mapa (estilo galeria Samsung) para a folha de detalhes. */
@Composable
fun PhotoDetailLocationMap(
    latitude: Double,
    longitude: Double,
    locationLabel: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) {
        MapView(context).apply { onCreate(null) }
    }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var markerPlaced by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            mapRef = map
            map.uiSettings.apply {
                isScrollGesturesEnabled = false
                isZoomGesturesEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false
            }
            map.setStyle(Style.Builder().fromJson(SatelliteMapStyle.json())) {
                styleReady = true
            }
        }
    }

    LaunchedEffect(mapRef, styleReady, latitude, longitude, markerPlaced) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady || markerPlaced) return@LaunchedEffect
        val target = LatLng(latitude, longitude)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 15.5))
        map.addMarker(
            MarkerOptions()
                .position(target)
                .title(locationLabel.orEmpty()),
        )
        markerPlaced = true
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
        )
        Text(
            SatelliteMapStyle.ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
