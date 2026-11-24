package id.ac.ceh

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import id.ac.ceh.data.Event
import id.ac.ceh.ui.HubTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.tile.TileOperation

class MapFragment : Fragment() {
    private val hub by activityViewModels<HubViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val nav = findNavController()
        val owner = viewLifecycleOwner
        val picking = arguments?.getBoolean(MapPick.MODE) == true
        val previous = if (picking) nav.previousBackStackEntry else null
        val initial = previous?.savedStateHandle?.get<DoubleArray>("editorCoordinates")
            ?.takeIf { it.size == 2 }?.let { validPoint(it[0], it[1]) }
        val state = mutableStateOf(MapContentState())
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(hub.events, hub.location) { events, location ->
                    MapContentState(
                        if (picking) emptyList() else events.mapNotNull { event ->
                            event.mapPoint()?.let { MapEvent(event, it) }
                        },
                        location?.let { validPoint(it.latitude, it.longitude) },
                    )
                }.collect { state.value = it }
            }
        }
        fun openEvent(id: Long) {
            if (nav.currentDestination?.id == R.id.mapFragment) {
                nav.navigate(R.id.eventDetailFragment, bundleOf("eventId" to id))
            }
        }
        (view as ComposeView).setContent {
            HubTheme {
                // The API 26 native render crash cannot be handled after constructing MapView.
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
                    UnavailableMapScreen(state.value.events, picking, { nav.popBackStack() }, ::openEvent)
                } else EventMapScreen(state.value, picking, initial, owner,
                    onLocate = hub::requestLocation,
                    onCancel = { nav.popBackStack() },
                    onPick = { point ->
                        if (previous != null && nav.currentDestination?.id == R.id.mapFragment) {
                            previous.savedStateHandle[MapPick.RESULT] = doubleArrayOf(point.latitude, point.longitude)
                            nav.popBackStack()
                        }
                    },
                    onOpen = ::openEvent,
                )
            }
        }
    }
}

private data class MapEvent(val event: Event, val point: EventPoint)
private data class MapContentState(val events: List<MapEvent> = emptyList(), val location: EventPoint? = null)
private enum class MapLoad { Loading, Ready, Failed }
private const val LIBERTY_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private val defaultCenter = EventPoint(-6.1754, 106.8272) // Kamera awal Jakarta, bukan lokasi acara.

@Composable
private fun UnavailableMapScreen(events: List<MapEvent>, picking: Boolean, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.map_unavailable_device), style = MaterialTheme.typography.titleMedium)
        }
        if (picking) {
            item { Text(stringResource(R.string.map_unavailable_editor)) }
            item {
                Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.map_back_editor))
                }
            }
        } else {
            item { Text(stringResource(R.string.map_list), style = MaterialTheme.typography.titleSmall) }
            if (events.isEmpty()) item { Text(stringResource(R.string.map_no_markers)) }
            items(events, key = { it.event.id }) { entry ->
                OutlinedCard(onClick = { onOpen(entry.event.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(entry.event.title, style = MaterialTheme.typography.titleMedium)
                        entry.event.location?.takeIf { it.isNotBlank() }?.let { Text(it) }
                        Text(entry.event.displayDate(entry.event.startTime), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventMapScreen(
    state: MapContentState,
    picking: Boolean,
    initialPoint: EventPoint?,
    owner: LifecycleOwner,
    onLocate: () -> Unit,
    onCancel: () -> Unit,
    onPick: (EventPoint) -> Unit,
    onOpen: (Long) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var focusRequested by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var showPlaces by remember { mutableStateOf(false) }
    key(attempt) {
        var load by remember { mutableStateOf(MapLoad.Loading) }
        var controller by remember { mutableStateOf<MapLibreMap?>(null) }

        LaunchedEffect(load) {
            if (load == MapLoad.Loading) {
                delay(20_000)
                load = MapLoad.Failed
            }
        }
        LaunchedEffect(controller, state.location, focusRequested) {
            val map = controller
            val fix = state.location
            if (map != null && focusRequested && fix != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(fix.latLng(), 14.0))
                focusRequested = false
            }
        }
        Column(Modifier.fillMaxSize()) {
            Text(
                stringResource(when (load) {
                    MapLoad.Loading -> R.string.map_loading
                    MapLoad.Failed -> if (picking) R.string.map_failed_pick else R.string.map_failed_browse
                    MapLoad.Ready -> when {
                        picking -> R.string.map_pick_hint
                        state.events.isEmpty() -> R.string.map_no_markers
                        else -> R.string.map_browse_hint
                    }
                }),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NativeEventMap(owner, state, initialPoint, picking, onReady = { controller = it },
                    onRendered = { if (load == MapLoad.Loading) load = MapLoad.Ready },
                    onError = { load = MapLoad.Failed }, onSelect = { selected = it },
                    canInteract = { load == MapLoad.Ready },
                )
                if (picking && load == MapLoad.Ready) {
                    Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.small) {
                        Text("+", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Text(stringResource(R.string.map_attribution), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                if (load == MapLoad.Failed) {
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.map_retry)) }
                } else {
                    TextButton(onClick = {
                        focusRequested = true
                        onLocate()
                    }) { Text(stringResource(R.string.map_locate)) }
                }
                if (picking) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.map_cancel)) }
                    if (load == MapLoad.Ready) {
                        Button(onClick = {
                            val point = controller?.cameraPosition?.target ?: return@Button
                            validPoint(point.latitude, point.wrap().longitude)?.let(onPick)
                        }) { Text(stringResource(R.string.map_use_point)) }
                    }
                } else if (state.events.isNotEmpty()) {
                    TextButton(onClick = { showPlaces = true }) { Text(stringResource(R.string.map_list)) }
                }
            }
        }
    }
    if (showPlaces) {
        AlertDialog(onDismissRequest = { showPlaces = false }, title = { Text(stringResource(R.string.map_list)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(state.events, key = { it.event.id }) { entry ->
                        TextButton(onClick = { showPlaces = false; selected = entry.event.id }) {
                            Text(entry.event.title)
                        }
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { showPlaces = false }) { Text(stringResource(R.string.map_close)) }
            })
    }
    state.events.firstOrNull { it.event.id == selected }?.event?.let { event ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(event.title) },
            text = { Text(listOfNotNull(event.displayDate(event.startTime), event.location).joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = { selected = null; onOpen(event.id) }) {
                    Text(stringResource(R.string.map_open_event))
                }
            }, dismissButton = {
                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.map_close)) }
            })
    }
}

@Suppress("DEPRECATION") // SDK annotations keep marker hit-testing independent of the provider's style layers.
@Composable
private fun NativeEventMap(
    owner: LifecycleOwner,
    state: MapContentState,
    initialPoint: EventPoint?,
    picking: Boolean,
    onReady: (MapLibreMap) -> Unit,
    onRendered: () -> Unit,
    onError: () -> Unit,
    onSelect: (Long) -> Unit,
    canInteract: () -> Boolean,
) {
    val latest by rememberUpdatedState(state)
    val interact by rememberUpdatedState(canInteract)
    val select by rememberUpdatedState(onSelect)
    val lifecycle = remember(owner) { MapViewLifecycle(owner.lifecycle) }
    var controller by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val markerIds = remember { mutableMapOf<Long, Long>() }
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MapLibre.getInstance(context)
            MapView(context).apply {
                lifecycle.attach(this)
                addOnDidFailLoadingMapListener { onError() }
                addOnTileActionListener { op, _, _, _, _, _, _ -> if (op == TileOperation.Error) onError() }
                addOnDidFinishRenderingMapListener { fully -> if (fully && controller?.style?.isFullyLoaded == true) onRendered() }
                getMapAsync { map ->
                    if (isDestroyed) return@getMapAsync
                    val center = initialPoint ?: latest.events.firstOrNull()?.point ?: defaultCenter
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(center.latLng(), 12.0))
                    if (picking) map.addOnMapLongClickListener { point ->
                        if (!interact()) return@addOnMapLongClickListener false
                        map.moveCamera(CameraUpdateFactory.newLatLng(point))
                        true
                    }
                    map.setOnMarkerClickListener { marker ->
                        if (interact()) markerIds[marker.id]?.let(select)
                        true
                    }
                    controller = map
                    onReady(map)
                    map.setStyle(LIBERTY_STYLE) { styleReady = true }
                }
            }
        },
        onRelease = { lifecycle.release() },
        update = {},
    )
    LaunchedEffect(controller, styleReady, state.events) {
        val map = controller ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        map.clear()
        markerIds.clear()
        if (state.events.isEmpty()) return@LaunchedEffect
        val pin = context.getDrawable(R.drawable.ic_event_pin)?.toBitmap()
            ?.let { IconFactory.getInstance(context).fromBitmap(it) }
        state.events.forEach { entry ->
            val options = MarkerOptions().position(entry.point.latLng()).title(entry.event.title)
            if (pin != null) options.icon(pin)
            val marker = map.addMarker(options)
            markerIds[marker.id] = entry.event.id
        }
    }
}

private fun EventPoint.latLng() = LatLng(latitude, longitude)

private class MapViewLifecycle(private val lifecycle: Lifecycle) : DefaultLifecycleObserver {
    private var view: MapView? = null
    private var started = false
    private var resumed = false

    fun attach(map: MapView) {
        view = map
        map.onCreate(null)
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { view?.onStart(); started = true }
    override fun onResume(owner: LifecycleOwner) { view?.onResume(); resumed = true }
    override fun onPause(owner: LifecycleOwner) { view?.onPause(); resumed = false }
    override fun onStop(owner: LifecycleOwner) { view?.onStop(); started = false }
    override fun onDestroy(owner: LifecycleOwner) = release()

    fun release() {
        val map = view ?: return
        view = null
        lifecycle.removeObserver(this)
        if (resumed) map.onPause()
        if (started) map.onStop()
        resumed = false
        started = false
        map.onDestroy()
    }
}
