package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/** Mapa + endereço no cartão (estilo galeria Samsung). Só compor quando há GPS. */
@Composable
fun PhotoDetailLocationSection(
    latitude: Double,
    longitude: Double,
    locationLabel: String?,
    modifier: Modifier = Modifier,
    contentOnDark: Boolean = false,
) {
    val addressColor = if (contentOnDark) {
        Color.White.copy(alpha = 0.92f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val cardBackground = if (contentOnDark) {
        Color(0xFF1E1E1E)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBackground),
    ) {
        PhotoDetailLocationMap(
            latitude = latitude,
            longitude = longitude,
            locationLabel = locationLabel,
            modifier = Modifier.fillMaxWidth(),
            mapCornerRadius = 0.dp,
        )
        locationLabel?.takeIf { it.isNotBlank() }?.let { address ->
            Text(
                address,
                style = MaterialTheme.typography.bodyMedium,
                color = addressColor,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
fun PhotoDetailLocationMap(
    latitude: Double,
    longitude: Double,
    locationLabel: String?,
    modifier: Modifier = Modifier,
    mapCornerRadius: androidx.compose.ui.unit.Dp = 12.dp,
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

    val shape = if (mapCornerRadius > 0.dp) {
        RoundedCornerShape(mapCornerRadius)
    } else {
        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(shape)
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
