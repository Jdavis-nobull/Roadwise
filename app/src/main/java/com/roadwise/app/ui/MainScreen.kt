package com.roadwise.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadwise.app.core.Units
import com.roadwise.app.search.CATEGORIES
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapView

@Composable
fun RoadwiseRoot(vm: MainViewModel, onExit: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(ui.toast) {
        ui.toast?.let { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it); vm.toastShown() }
    }
    BackHandler { if (!vm.back()) onExit() }

    RoadwiseTheme(dark = ui.night) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            MapHost(vm)

            when {
                nav.active -> NavHud(vm, nav, ui)
                ui.selectingArea -> AreaSelectOverlay(vm, ui)
                else -> BrowseOverlay(vm, ui, online)
            }

            Box(Modifier.align(Alignment.BottomCenter)) {
                SheetHost(vm, ui, nav.active)
            }

            if (ui.reportPicker) ReportPicker(vm)
            if (ui.shareDialog) ShareDialog(vm)
            if (ui.layersSheet) LayersSheet(vm)

            AnimatedVisibility(ui.screen != Screen.MAP, enter = slideInVertically { it / 6 } + fadeIn(), exit = fadeOut()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (ui.screen) {
                        Screen.SEARCH -> SearchScreen(vm, ui)
                        Screen.OFFLINE -> OfflineScreen(vm)
                        Screen.SETTINGS -> SettingsScreen(vm)
                        Screen.MAP -> {}
                    }
                }
            }

            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
        }
    }
}

@Composable
private fun MapHost(vm: MainViewModel) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(ctx).apply { onCreate(null) } }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.detachMap() }
    }
    AndroidView(
        factory = {
            mapView.also { mv ->
                mv.addOnDidFinishLoadingStyleListener { vm.map?.onStyleFinishedLoading() }
                mv.getMapAsync { m -> vm.attachMap(MapController(ctx, m)) }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
fun rememberVoiceSearch(onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onResult)
        }
    }
    return {
        runCatching {
            launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Where to?")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        }
    }
}

@Composable
private fun BrowseOverlay(vm: MainViewModel, ui: UiState, online: Boolean) {
    // Offline-download prompt shown in the bottom card (until dismissed or something is downloaded).
    val areas by vm.areas.collectAsStateWithLifecycle()
    val promptState = remember(areas.isEmpty()) { if (areas.isEmpty()) vm.myState() else null }
        ?.takeIf { !ui.offlinePromptHidden }
    val fabBottom = if (promptState != null) 240.dp else 150.dp
    val voice = rememberVoiceSearch { vm.openScreen(Screen.SEARCH); vm.setQuery(it) }
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Surface(
                shape = RoundedCornerShape(28.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth().height(54.dp).clickable { vm.openScreen(Screen.SEARCH) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, end = 6.dp)) {
                    Icon(Icons.Filled.Place, null, tint = RwColors.Red)
                    Spacer(Modifier.width(12.dp))
                    Text(if (ui.category != null) ui.category else ui.query.ifBlank { "Search here" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                    if (ui.results.isNotEmpty()) IconButton(onClick = { vm.clearResults() }) { Icon(Icons.Filled.Close, "Clear results") }
                    IconButton(onClick = voice) { Icon(Icons.Filled.Mic, "Voice search", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { vm.openScreen(Screen.SETTINGS) }) { Icon(Icons.Filled.Settings, "Settings") }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CATEGORIES) { cat ->
                    Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 3.dp, tonalElevation = 2.dp,
                        modifier = Modifier.clickable { vm.searchCategory(cat) }) {
                        Text("${cat.emoji} ${cat.label}", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 14.sp)
                    }
                }
            }
            if (!online) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF37474F), contentColor = Color.White,
                    modifier = Modifier.clickable { vm.openScreen(Screen.OFFLINE) }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudOff, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Offline - using downloaded maps", fontSize = 13.sp)
                    }
                }
            }
        }

        // Map type (Default / Satellite), top right like Google Maps.
        SmallFloatingActionButton(onClick = { vm.openLayers(true) }, containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 136.dp, end = 14.dp)) {
            Icon(Icons.Filled.Layers, "Map type")
        }

        // Right-side controls
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = if (ui.sheet == Sheet.None) fabBottom else 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
            val live by vm.liveShare.collectAsStateWithLifecycle()
            SmallFloatingActionButton(onClick = { vm.openShareDialog(true) },
                containerColor = if (live.active) RwColors.Red else MaterialTheme.colorScheme.surface,
                contentColor = if (live.active) Color.White else MaterialTheme.colorScheme.onSurface) {
                Icon(Icons.Filled.ShareLocation, "Share location")
            }
            SmallFloatingActionButton(onClick = { vm.openScreen(Screen.OFFLINE) }, containerColor = MaterialTheme.colorScheme.surface) {
                Icon(Icons.Filled.DownloadForOffline, "Offline maps")
            }
            FloatingActionButton(onClick = { vm.recenter() }, containerColor = MaterialTheme.colorScheme.surface, shape = CircleShape) {
                Icon(if (ui.following) Icons.Filled.MyLocation else Icons.Filled.LocationSearching, "My location", tint = MaterialTheme.colorScheme.primary)
            }
        }
        // Report button (Waze-style)
        if (ui.sheet == Sheet.None) {
            FloatingActionButton(
                onClick = { vm.openReportPicker(true) }, containerColor = RwColors.Orange, contentColor = Color.White,
                shape = CircleShape, modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = fabBottom),
            ) { Icon(Icons.Filled.Campaign, "Report") }

            WhereToCard(vm, ui, promptState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun WhereToCard(vm: MainViewModel, ui: UiState, mine: com.roadwise.app.offline.Region?, modifier: Modifier) {
    Surface(modifier.fillMaxWidth().padding(10.dp), shape = RoundedCornerShape(22.dp), shadowElevation = 10.dp, tonalElevation = 2.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Where to?", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                if (ui.results.isNotEmpty()) TextButton(onClick = { vm.openScreen(Screen.SEARCH) }) { Text("${ui.results.size} results") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickChip(Icons.Filled.Home, "Home", Modifier.weight(1f)) { vm.navigateHomeOrWork("home") }
                QuickChip(Icons.Filled.Work, "Work", Modifier.weight(1f)) { vm.navigateHomeOrWork("work") }
                QuickChip(Icons.Filled.DownloadForOffline, "Offline", Modifier.weight(1f)) { vm.openScreen(Screen.OFFLINE) }
            }
            // First-run nudge: one tap to make the whole home state work offline.
            if (mine != null) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth().clickable { vm.downloadRegions(listOf(mine)) }) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DownloadForOffline, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Get all of ${mine.name} offline", fontWeight = FontWeight.Medium)
                            Text("Maps & directions with no signal", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Download", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { vm.dismissOfflinePrompt() }) {
                            Icon(Icons.Filled.Close, "Not now", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(vertical = 10.dp, horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun AreaSelectOverlay(vm: MainViewModel, ui: UiState) {
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { name = vm.suggestAreaName() }
    val est = ui.areaEstimate
    val tooBig = est != null && est.tiles > 450_000
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Frame matching the area that will be downloaded
        Box(
            Modifier
                .padding(start = maxWidth * 0.08f, end = maxWidth * 0.08f, top = maxHeight * 0.128f, bottom = maxHeight * 0.176f)
                .fillMaxSize()
                .border(BorderStroke(3.dp, if (tooBig) RwColors.Red else RwColors.Blue), RoundedCornerShape(6.dp))
        )
        Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp) {
            Text("Pan and zoom so the box covers the area you want offline", Modifier.padding(14.dp), fontWeight = FontWeight.Medium)
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(10.dp), shape = RoundedCornerShape(22.dp), shadowElevation = 10.dp) {
            Column(Modifier.padding(16.dp)) {
                if (est != null) {
                    Text(
                        if (tooBig) "Area too large - zoom in a bit (max about a large US state)"
                        else "About ${mb(est.mapMb)} of map + ${if (est.routingMbGuess > 0) "~${mb(est.routingMbGuess)} routing data" else "routing data already downloaded"}",
                        color = if (tooBig) RwColors.Red else MaterialTheme.colorScheme.onSurface,
                    )
                    Text("${"%,d".format(est.tiles)} map tiles · includes search index and speed cameras", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.cancelAreaSelect() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { scope.launch { vm.confirmArea(name) } }, enabled = !tooBig && est != null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text("Download")
                    }
                }
            }
        }
    }
}

fun mb(v: Double): String = if (v >= 1024) "%.1f GB".format(v / 1024) else "${v.toInt().coerceAtLeast(1)} MB"

fun Modifier.fabShadow() = this.shadow(6.dp, CircleShape)

@Composable
fun SpeedBadge(speedText: String, unit: String, over: Boolean) {
    Surface(shape = CircleShape, color = if (over) RwColors.Red else MaterialTheme.colorScheme.surface, shadowElevation = 6.dp,
        modifier = Modifier.size(68.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(speedText, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (over) Color.White else MaterialTheme.colorScheme.onSurface)
            Text(unit, fontSize = 11.sp, color = if (over) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LimitSign(limit: Int, imperial: Boolean) {
    if (imperial) {
        Surface(color = Color.White, shape = RoundedCornerShape(6.dp), border = BorderStroke(2.dp, Color.Black), shadowElevation = 4.dp,
            modifier = Modifier.width(52.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("SPEED", fontSize = 8.sp, color = Color.Black, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
                Text("LIMIT", fontSize = 8.sp, color = Color.Black, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
                Text("$limit", fontSize = 22.sp, color = Color.Black, fontWeight = FontWeight.Black)
            }
        }
    } else {
        Box(Modifier.size(54.dp).background(Color.White, CircleShape).border(5.dp, RwColors.Red, CircleShape), contentAlignment = Alignment.Center) {
            Text("$limit", fontSize = 20.sp, color = Color.Black, fontWeight = FontWeight.Black)
        }
    }
}

fun speedLabel(mps: Double, imperial: Boolean) = Units.speed(mps, imperial).toString()
