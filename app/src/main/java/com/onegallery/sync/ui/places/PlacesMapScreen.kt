package com.onegallery.sync.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.ui.gallery.GalleryViewModel
import com.onegallery.sync.ui.gallery.MediaThumbnailImage
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val MAP_STYLE_URL = "https://demotiles.maplibre.org/style.json"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesMapScreen(
    viewModel: GalleryViewModel,
    syncCoordinator: SyncCoordinator,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
    onShowAllAtPlace: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clusters = remember(state.geoItems) { buildPhotoMapClusters(state.geoItems) }
    val latestClusters by rememberUpdatedState(clusters)
    var selectedCluster by remember { mutableStateOf<PhotoMapCluster?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    val markers = remember { mutableStateListOf<Marker>() }
    var styleReady by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle, mapView) {
        val view = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view?.onStart()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_STOP -> view?.onStop()
                Lifecycle.Event.ON_DESTROY -> view?.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view?.onDestroy()
        }
    }

    LaunchedEffect(clusters, mapRef, styleReady, latestClusters) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        map.setOnMarkerClickListener { marker ->
            val match = latestClusters.minByOrNull { cluster ->
                val dLat = cluster.latitude - marker.position.latitude
                val dLon = cluster.longitude - marker.position.longitude
                dLat * dLat + dLon * dLon
            }
            match?.let { selectedCluster = it }
            true
        }
        markers.forEach { map.removeMarker(it) }
        markers.clear()
        clusters.forEach { cluster ->
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(cluster.latitude, cluster.longitude))
                    .title(cluster.title)
                    .snippet(cluster.items.size.toString()),
            )
            if (marker != null) markers.add(marker)
        }
        if (clusters.isNotEmpty()) {
            val builder = LatLngBounds.Builder()
            clusters.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 96))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mapa de fotos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        when {
            clusters.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.Map,
                        contentDescription = null,
                        modifier = Modifier.padding(bottom = 12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Nenhuma foto com GPS no índice. Sincronize de novo (Fotos → Sync) para reler EXIF. " +
                            "Fotos só na nuvem precisam do original baixado para ter GPS.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Ative localização na câmera e sincronize de novo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Voltar")
                    }
                }
            }
            else -> {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    factory = { ctx ->
                        MapView(ctx).also { created ->
                            mapView = created
                            created.getMapAsync { map ->
                                mapRef = map
                                map.uiSettings.isCompassEnabled = true
                                map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) {
                                    styleReady = true
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    val cluster = selectedCluster
    if (cluster != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedCluster = null },
            sheetState = sheetState,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(cluster.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${cluster.items.size} ${if (cluster.items.size == 1) "foto" else "fotos"} neste ponto",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TextButton(
                    onClick = {
                        selectedCluster = null
                        onShowAllAtPlace(cluster.title)
                    },
                ) {
                    Text("Ver todas na galeria")
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(cluster.items, key = { it.canonicalPath }) { item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                selectedCluster = null
                                val encoded = URLEncoder.encode(
                                    item.canonicalPath,
                                    StandardCharsets.UTF_8.name(),
                                )
                                onOpenItem(encoded)
                            },
                    ) {
                        MediaThumbnailImage(
                            item = item,
                            syncCoordinator = syncCoordinator,
                            contentDescription = item.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }
}
