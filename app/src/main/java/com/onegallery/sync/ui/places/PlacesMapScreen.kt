package com.onegallery.sync.ui.places

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.onegallery.sync.ui.gallery.MediaThumbnailImage
import com.onegallery.sync.ui.gallery.formatMediaCaptureDate
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
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
    onShowAllAtPlace: (String) -> Unit,
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
    val pins = remember(state.geoItems) { buildPhotoMapPins(state.geoItems) }
    var selectedPin by remember { mutableStateOf<PhotoMapPin?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
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
                        syncCoordinator = syncCoordinator,
                        deviceLocationGranted = deviceLocationGranted,
                        locateUserRequest = locateUserRequest,
                        onPinSelected = { selectedPin = it },
                        onRequestDeviceLocationPermission = {
                            pendingLocateAfterPermission = true
                            deviceLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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

    val pin = selectedPin
    if (pin != null) {
        val item = pin.item
        ModalBottomSheet(
            onDismissRequest = { selectedPin = null },
            sheetState = sheetState,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    item.locationLabel?.takeIf { it.isNotBlank() } ?: item.displayName,
                    style = MaterialTheme.typography.titleLarge,
                )
                if (item.locationLabel?.isNotBlank() == true) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val dateLine = formatMediaCaptureDate(item.sortTimestamp).takeIf { it.isNotBlank() }
                Text(
                    listOfNotNull(
                        dateLine,
                        formatPinCoordinates(pin.latitude, pin.longitude),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                item.locationLabel?.takeIf { it.isNotBlank() }?.let { label ->
                    TextButton(
                        onClick = {
                            selectedPin = null
                            onShowAllAtPlace(label)
                        },
                    ) {
                        Text("Ver outras fotos neste lugar")
                    }
                }
            }
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
                    .clickable {
                        selectedPin = null
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

private fun formatPinCoordinates(latitude: Double, longitude: Double): String {
    return String.format(Locale.US, "%.5f°, %.5f°", latitude, longitude)
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
    syncCoordinator: SyncCoordinator,
    deviceLocationGranted: Boolean,
    locateUserRequest: Int,
    onPinSelected: (PhotoMapPin) -> Unit,
    onRequestDeviceLocationPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) {
        MapView(context).apply { onCreate(null) }
    }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val markers = remember { mutableStateListOf<Marker>() }
    val markerToPin = remember { mutableStateMapOf<Marker, PhotoMapPin>() }
    var mapInitialized by remember { mutableStateOf(false) }
    var initialCameraApplied by remember { mutableStateOf(false) }
    var locationComponentReady by remember { mutableStateOf(false) }

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

    LaunchedEffect(mapRef, styleReady, deviceLocationGranted) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        if (deviceLocationGranted && !locationComponentReady) {
            runCatching {
                val style = map.style ?: return@LaunchedEffect
                val component = map.locationComponent
                if (!component.isLocationComponentEnabled) {
                    component.activateLocationComponent(
                        LocationComponentActivationOptions.builder(context, style)
                            .useDefaultLocationEngine(true)
                            .build(),
                    )
                    component.isLocationComponentEnabled = true
                    component.cameraMode = CameraMode.NONE
                }
                locationComponentReady = true
            }
        }
    }

    LaunchedEffect(mapRef, styleReady, pins, initialCameraApplied) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady || initialCameraApplied || pins.isEmpty()) return@LaunchedEffect
        initialCameraApplied = true
        animateCameraToPhotoPins(map, pins)
    }

    LaunchedEffect(locateUserRequest, mapRef, styleReady, deviceLocationGranted, locationComponentReady) {
        if (locateUserRequest == 0) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        if (!deviceLocationGranted) {
            onRequestDeviceLocationPermission()
            return@LaunchedEffect
        }
        val component = map.locationComponent
        if (!component.isLocationComponentEnabled) return@LaunchedEffect
        component.cameraMode = CameraMode.NONE
        val fromEngine = component.lastKnownLocation
        val target = when {
            fromEngine != null -> LatLng(fromEngine.latitude, fromEngine.longitude)
            else -> lastKnownDeviceLatLng(context)
        }
        if (target != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15.0))
        }
    }

    LaunchedEffect(pins, mapRef, styleReady, syncCoordinator) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        map.setOnMarkerClickListener { marker ->
            markerToPin[marker]?.let { onPinSelected(it) }
            true
        }
        markers.forEach { map.removeMarker(it) }
        markers.clear()
        markerToPin.clear()
        val iconFactory = IconFactory.getInstance(context)
        val markerArt = withContext(Dispatchers.Default) {
            pins.map { pin ->
                val bitmap = PhotoStackMarkerBitmap.create(
                    context = context,
                    items = listOf(pin.item),
                    syncCoordinator = syncCoordinator,
                )
                Triple(
                    pin,
                    bitmap,
                    pin.item.locationLabel?.takeIf { it.isNotBlank() } ?: pin.item.displayName,
                )
            }
        }
        markerArt.forEach { (pin, stackBitmap, title) ->
            val added = map.addMarker(
                org.maplibre.android.annotations.MarkerOptions()
                    .position(LatLng(pin.latitude, pin.longitude))
                    .title(title)
                    .snippet(pin.item.displayName)
                    .icon(iconFactory.fromBitmap(stackBitmap)),
            )
            markers.add(added)
            markerToPin[added] = pin
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
    }
}
