package com.onegallery.sync.ui.places

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onegallery.sync.R
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.data.local.MediaAccessPermissions
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncTaskLauncher
import com.onegallery.sync.ui.gallery.GalleryViewModel
import com.onegallery.sync.ui.theme.ZeroScaffoldContentInsets
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesMapScreen(
    viewModel: GalleryViewModel,
    syncCoordinator: SyncCoordinator,
    syncTasks: SyncTaskLauncher,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    val context = LocalContext.current
    val hasMediaLocation = MediaAccessPermissions.hasMediaLocationAccess(context)
    val coordinatorState by syncCoordinator.state.collectAsStateWithLifecycle()
    val mediaLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) syncTasks.runBidirectionalSync(userInitiated = true)
    }
    var deviceLocationGranted by remember {
        mutableStateOf(MediaAccessPermissions.hasDeviceLocationAccess(context))
    }
    var locateUserRequest by remember { mutableStateOf(0) }
    var pendingLocateAfterPermission by remember { mutableStateOf(false) }
    val deviceLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        deviceLocationGranted = granted
        if (granted && pendingLocateAfterPermission) {
            pendingLocateAfterPermission = false
            locateUserRequest++
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pinSignature = remember(state.geoItems) { buildPhotoMapPinSignature(state.geoItems) }
    val pins = remember(pinSignature) { buildPhotoMapPins(state.geoItems) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = ZeroScaffoldContentInsets,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mapa de fotos")
                        if (pins.isNotEmpty()) {
                            Text(
                                "${pins.size} ${if (pins.size == 1) "local" else "locais"} · " +
                                    stringResource(R.string.map_photo_locations_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        when {
            pins.isEmpty() -> {
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
                        if (!hasMediaLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            "O mapa precisa da permissão “Localização das mídias” (GPS no EXIF). " +
                                "Não confundir com a localização do aparelho na câmera."
                        } else {
                            "Nenhuma foto com GPS no índice. Em Sync → Sincronizar agora o app " +
                                "indexa a biblioteca e a localização junto."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!hasMediaLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Button(
                            onClick = {
                                mediaLocationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            enabled = !coordinatorState.isRunning,
                        ) { Text("Permitir localização das mídias") }
                    }
                    OutlinedButton(
                        onClick = { syncTasks.runBidirectionalSync(userInitiated = true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        enabled = !coordinatorState.isRunning &&
                            (hasMediaLocation || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q),
                    ) {
                        Text(
                            if (coordinatorState.isRunning) "Sincronizando…"
                            else "Sincronizar agora (indexa GPS)",
                        )
                    }
                    TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Voltar")
                    }
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    PlacesMapView(
                        modifier = Modifier.fillMaxSize(),
                        pins = pins,
                        pinSignature = pinSignature,
                        syncCoordinator = syncCoordinator,
                        deviceLocationGranted = deviceLocationGranted,
                        locateUserRequest = locateUserRequest,
                        onPinSelected = { pin ->
                            viewModel.clearBrowseFilters()
                            val encoded = URLEncoder.encode(
                                pin.item.canonicalPath,
                                StandardCharsets.UTF_8.name(),
                            )
                            onOpenItem(encoded)
                        },
                        onRequestDeviceLocationPermission = {
                            pendingLocateAfterPermission = true
                            deviceLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        onLocateFailed = {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Não foi possível obter sua localização. Ative o GPS e tente de novo.",
                                )
                            }
                        },
                    )
                    SmallFloatingActionButton(
                        onClick = {
                            if (deviceLocationGranted) {
                                locateUserRequest++
                            } else {
                                pendingLocateAfterPermission = true
                                deviceLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 56.dp),
                    ) {
                        Icon(
                            Icons.Outlined.MyLocation,
                            contentDescription = stringResource(R.string.map_my_location),
                        )
                    }
                }
            }
        }
    }

}

/** Enquadra os locais das fotos (comportamento próximo ao mapa do app Fotos no iPhone). */
private fun animateCameraToPhotoPins(map: MapLibreMap, pins: List<PhotoMapPin>) {
    if (pins.isEmpty()) return
    runCatching {
        if (pins.size == 1) {
            val p = pins.first()
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 14.0),
            )
        } else {
            val builder = LatLngBounds.Builder()
            pins.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120))
        }
    }
}

@Composable
private fun PlacesMapView(
    pins: List<PhotoMapPin>,
    pinSignature: String,
    syncCoordinator: SyncCoordinator,
    deviceLocationGranted: Boolean,
    locateUserRequest: Int,
    onPinSelected: (PhotoMapPin) -> Unit,
    onRequestDeviceLocationPermission: () -> Unit,
    onLocateFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) {
        MapView(context).apply { onCreate(null) }
    }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val markerByPath = remember { mutableStateMapOf<String, Pair<Marker, PhotoMapPin>>() }
    var mapInitialized by remember { mutableStateOf(false) }
    var initialCameraApplied by rememberSaveable { mutableStateOf(false) }
    var mountingPins by remember(pinSignature) { mutableStateOf(pins.isNotEmpty()) }

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
        if (mapInitialized) return@LaunchedEffect
        mapInitialized = true
        mapView.getMapAsync { map ->
            mapRef = map
            map.uiSettings.isCompassEnabled = true
            map.setStyle(Style.Builder().fromJson(SatelliteMapStyle.json())) {
                styleReady = true
            }
        }
    }

    LaunchedEffect(mapRef, styleReady, pins, initialCameraApplied) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady || initialCameraApplied || pins.isEmpty()) return@LaunchedEffect
        initialCameraApplied = true
        animateCameraToPhotoPins(map, pins)
    }

    LaunchedEffect(locateUserRequest, mapRef, styleReady, deviceLocationGranted) {
        if (locateUserRequest == 0) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        if (!deviceLocationGranted) {
            onRequestDeviceLocationPermission()
            return@LaunchedEffect
        }
        val target = withContext(Dispatchers.IO) { fetchDeviceLatLng(context) }
        if (target != null) {
            runCatching {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15.0))
            }.onFailure { onLocateFailed() }
        } else {
            onLocateFailed()
        }
    }

    LaunchedEffect(pinSignature, mapRef, styleReady, syncCoordinator, pins) {
        if (pins.isEmpty()) {
            mountingPins = false
            return@LaunchedEffect
        }
        mountingPins = true
        val map = mapRef
        if (map == null || !styleReady) return@LaunchedEffect
        map.setOnMarkerClickListener { marker ->
            markerByPath.values.firstOrNull { it.first == marker }?.second?.let { onPinSelected(it) }
            true
        }
        val pinByPath = pins.associateBy { it.item.canonicalPath }
        val stalePaths = markerByPath.keys.filter { it !in pinByPath }
        stalePaths.forEach { path ->
            markerByPath[path]?.first?.let { map.removeMarker(it) }
            markerByPath.remove(path)
        }
        val iconFactory = IconFactory.getInstance(context)
        val toAdd = pinByPath.filterKeys { it !in markerByPath }
        if (toAdd.isEmpty()) {
            mountingPins = false
            return@LaunchedEffect
        }
        try {
            val markerArt = withContext(Dispatchers.Default) {
                toAdd.values.map { pin ->
                    val bitmap = PhotoStackMarkerBitmap.create(
                        context = context,
                        items = listOf(pin.item),
                        syncCoordinator = syncCoordinator,
                    )
                    pin to bitmap
                }
            }
            markerArt.forEach { (pin, stackBitmap) ->
                val path = pin.item.canonicalPath
                if (path in markerByPath) return@forEach
                val added = map.addMarker(
                    org.maplibre.android.annotations.MarkerOptions()
                        .position(LatLng(pin.latitude, pin.longitude))
                        .icon(iconFactory.fromBitmap(stackBitmap)),
                )
                markerByPath[path] = added to pin
            }
        } finally {
            mountingPins = false
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
        )
        Text(
            SatelliteMapStyle.ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        if (mountingPins) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(22.dp)
                    .background(
                        Color.Black.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(5.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        }
    }
}
