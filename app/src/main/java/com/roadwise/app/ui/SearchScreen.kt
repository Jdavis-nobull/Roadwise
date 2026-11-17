package com.roadwise.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadwise.app.core.Place
import com.roadwise.app.core.Units
import com.roadwise.app.search.CATEGORIES

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(vm: MainViewModel, ui: UiState) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val voice = rememberVoiceSearch { vm.setQuery(it) }
    LaunchedEffect(Unit) { if (ui.category == null) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openScreen(Screen.MAP) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(
                value = ui.query, onValueChange = { vm.setQuery(it) },
                placeholder = { Text(ui.category ?: "Search places, addresses, coordinates") },
                singleLine = true, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1f).focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
                trailingIcon = {
                    if (ui.query.isNotEmpty() || ui.category != null) IconButton(onClick = { vm.clearResults() }) { Icon(Icons.Filled.Close, "Clear") }
                    else IconButton(onClick = voice) { Icon(Icons.Filled.Mic, "Voice search") }
                },
            )
        }
        if (!online) Text("Offline: searching saved places and downloaded areas only", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        if (ui.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        val showResults = ui.query.trim().length >= 2 || ui.category != null
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (showResults) {
                if (ui.results.size > 1) item {
                    TextButton(onClick = { vm.showResultsOnMap() }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Icon(Icons.Filled.Map, null); Spacer(Modifier.width(6.dp)); Text("Show ${ui.results.size} on map")
                    }
                }
                items(ui.results) { p -> PlaceRow(p, iconFor(p), vm.distanceTo(p.latLon), settings.imperial) { vm.selectPlace(p) } }
                if (!ui.searching && ui.results.isEmpty()) item {
                    Text(if (online) "No results" else "No results offline. Download this area in Offline maps to search it without signal.",
                        Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                item {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickChip(Icons.Filled.Home, "Home", Modifier.weight(1f)) { vm.navigateHomeOrWork("home") }
                        QuickChip(Icons.Filled.Work, "Work", Modifier.weight(1f)) { vm.navigateHomeOrWork("work") }
                    }
                }
                item { SectionTitle("Nearby") }
                item {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        CATEGORIES.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { cat ->
                                    Column(Modifier.weight(1f).clickable { vm.searchCategory(cat) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(52.dp)) {
                                            Box(contentAlignment = Alignment.Center) { Text(cat.emoji, fontSize = 24.sp) }
                                        }
                                        Text(cat.label, fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
                val favs = ui.saved.filter { it.kind == "fav" || it.kind == "home" || it.kind == "work" }
                if (favs.isNotEmpty()) {
                    item { SectionTitle("Saved places") }
                    items(favs) { sp ->
                        val icon = when (sp.kind) { "home" -> Icons.Filled.Home; "work" -> Icons.Filled.Work; else -> Icons.Filled.Favorite }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            PlaceRow(sp.place, icon, vm.distanceTo(sp.place.latLon), settings.imperial, onLong = { menu = true }) { vm.selectPlace(sp.place) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; vm.deleteSaved(sp) })
                            }
                        }
                    }
                }
                if (ui.recents.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Recent", Modifier.weight(1f))
                            TextButton(onClick = { vm.clearRecents() }) { Text("Clear") }
                        }
                    }
                    items(ui.recents) { p -> PlaceRow(p, Icons.Filled.History, vm.distanceTo(p.latLon), settings.imperial) { vm.selectPlace(p) } }
                }
            }
        }
    }
}

private fun iconFor(p: Place): ImageVector {
    val c = p.category.lowercase()
    return when {
        p.source == "saved" -> Icons.Filled.Favorite
        c.contains("fuel") || c.contains("gas") -> Icons.Filled.LocalGasStation
        c.contains("restaurant") || c.contains("fast") || c.contains("food") -> Icons.Filled.Restaurant
        c.contains("cafe") || c.contains("coffee") -> Icons.Filled.LocalCafe
        c.contains("parking") -> Icons.Filled.LocalParking
        c.contains("charging") || c.contains("ev") -> Icons.Filled.EvStation
        c.contains("hotel") || c.contains("lodging") || c.contains("motel") -> Icons.Filled.Hotel
        c.contains("hospital") -> Icons.Filled.LocalHospital
        c.contains("grocery") || c.contains("supermarket") -> Icons.Filled.ShoppingCart
        c.startsWith("road") -> Icons.Filled.AddRoad
        c.startsWith("place") || c in setOf("city", "town", "village") -> Icons.Filled.LocationCity
        c.contains("airport") -> Icons.Filled.Flight
        c.contains("park") -> Icons.Filled.Park
        else -> Icons.Filled.Place
    }
}

@Composable
private fun SectionTitle(t: String, modifier: Modifier = Modifier) {
    Text(t, modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(p: Place, icon: ImageVector, dist: Double?, imperial: Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(p.category.takeIf { it.isNotBlank() && it !in setOf("pin", "coords") }?.replaceFirstChar { it.uppercase() } ?: "", p.address)
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (dist != null) Text(Units.distance(dist, imperial), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (p.source == "offline") {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.OfflinePin, "Offline result", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
