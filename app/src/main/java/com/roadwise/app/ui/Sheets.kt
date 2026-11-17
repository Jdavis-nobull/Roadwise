package com.roadwise.app.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadwise.app.core.Place
import com.roadwise.app.core.Report
import com.roadwise.app.core.Units
import com.roadwise.app.reports.ReportType
import com.roadwise.app.routing.TravelMode
import java.text.DateFormat
import java.util.Date

@Composable
fun SheetHost(vm: MainViewModel, ui: UiState, navigating: Boolean) {
    val sheet = ui.sheet
    if (sheet == Sheet.None) return
    if (navigating && sheet !is Sheet.ReportInfo) return
    Surface(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
        shape = RoundedCornerShape(24.dp), shadowElevation = 12.dp, tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            when (sheet) {
                is Sheet.PlaceInfo -> PlaceSheet(vm, sheet.place)
                is Sheet.Routes -> RoutesSheet(vm, sheet)
                is Sheet.ReportInfo -> ReportSheet(vm, sheet.report)
                Sheet.None -> {}
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String?, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 2)
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
    }
}

@Composable
private fun PlaceSheet(vm: MainViewModel, p: Place) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var saveMenu by remember { mutableStateOf(false) }
    val dist = vm.distanceTo(p.latLon)
    val sub = listOfNotNull(
        p.category.takeIf { it.isNotBlank() && it != "pin" && it != "coords" }?.replaceFirstChar { it.uppercase() },
        dist?.let { Units.distance(it, settings.imperial) + " away" },
    ).joinToString(" · ")
    SheetHeader(p.name, sub) { vm.closeSheet() }
    if (p.address.isNotBlank()) Text(p.address, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.directions(p) }, modifier = Modifier.weight(1.3f)) {
            Icon(Icons.Filled.Directions, null); Spacer(Modifier.width(6.dp)); Text("Directions")
        }
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { saveMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.BookmarkBorder, null); Spacer(Modifier.width(4.dp)); Text("Save")
            }
            DropdownMenu(saveMenu, { saveMenu = false }) {
                DropdownMenuItem(text = { Text("Set as Home") }, leadingIcon = { Icon(Icons.Filled.Home, null) }, onClick = { saveMenu = false; vm.savePlace(p, "home") })
                DropdownMenuItem(text = { Text("Set as Work") }, leadingIcon = { Icon(Icons.Filled.Work, null) }, onClick = { saveMenu = false; vm.savePlace(p, "work") })
                DropdownMenuItem(text = { Text("Favorites") }, leadingIcon = { Icon(Icons.Filled.Favorite, null) }, onClick = { saveMenu = false; vm.savePlace(p, "fav") })
            }
        }
        IconButton(onClick = {
            val text = "${p.name}\n${p.address}\nhttps://www.openstreetmap.org/?mlat=${p.lat}&mlon=${p.lon}#map=17/${p.lat}/${p.lon}"
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share place"))
        }) { Icon(Icons.Filled.Share, "Share") }
    }
}

@Composable
private fun RoutesSheet(vm: MainViewModel, s: Sheet.Routes) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    SheetHeader("To ${s.dest.name}", s.dest.address) { vm.closeSheet() }
    Spacer(Modifier.height(6.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        TravelMode.entries.forEachIndexed { i, m ->
            SegmentedButton(selected = s.mode == m, onClick = { vm.setMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, TravelMode.entries.size),
                icon = {
                    Icon(when (m) { TravelMode.DRIVE -> Icons.Filled.DirectionsCar; TravelMode.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike; TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk },
                        null, Modifier.size(18.dp))
                }) {
                Text(m.label)
            }
        }
    }
    if (s.mode == TravelMode.DRIVE) {
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(settings.avoidTolls, { vm.updateSettings { it.copy(avoidTolls = !it.avoidTolls) }; vm.directions(s.dest, s.mode) }, { Text("Avoid tolls") })
            FilterChip(settings.avoidHighways, { vm.updateSettings { it.copy(avoidHighways = !it.avoidHighways) }; vm.directions(s.dest, s.mode) }, { Text("Avoid highways") })
            FilterChip(settings.avoidFerries, { vm.updateSettings { it.copy(avoidFerries = !it.avoidFerries) }; vm.directions(s.dest, s.mode) }, { Text("Ferries") })
        }
    }
    Spacer(Modifier.height(6.dp))
    when {
        s.loading -> Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Finding the best routes...")
        }
        s.error != null -> Column(Modifier.padding(vertical = 8.dp)) {
            Text(s.error, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.directions(s.dest, s.mode) }) { Text("Try again") }
                TextButton(onClick = { vm.openScreen(Screen.OFFLINE) }) { Text("Offline maps") }
            }
        }
        else -> {
            Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.routes.forEachIndexed { i, r ->
                    val sel = i == s.selected
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (sel) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.fillMaxWidth().clickable { vm.selectRoute(i) },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Units.duration(r.durationS), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                    color = if (i == 0) RwColors.Green else MaterialTheme.colorScheme.onSurface)
                                Text("${Units.distance(r.distanceM, settings.imperial)} · ${r.label}", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val tags = listOfNotNull(
                                    if (i == 0) "Best route" else null,
                                    if (r.hasTolls) "Tolls" else null,
                                    if (r.hasFerry) "Ferry" else null,
                                    if (r.source == "offline") "Offline route" else null,
                                )
                                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("Arrive ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (r.durationS * 1000).toLong()))}",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.startNavigation() }, modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RwColors.Blue)) {
                    Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp)); Text("Start", fontSize = 18.sp)
                }
                OutlinedButton(onClick = { vm.startNavigation(simulate = true) }, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Preview")
                }
            }
        }
    }
}

@Composable
private fun ReportSheet(vm: MainViewModel, r: Report) {
    val t = ReportType.of(r.type)
    val mins = ((System.currentTimeMillis() - r.created) / 60_000).coerceAtLeast(0)
    val age = if (mins < 60) "$mins min ago" else "${mins / 60} hr ago"
    SheetHeader("${t.emoji}  ${t.label}", "Reported $age" + (if (r.by.isNotBlank()) " by ${r.by}" else "") + (if (r.up > 0) " · ${r.up} confirmed" else "")) { vm.closeSheet() }
    if (r.note.isNotBlank()) Text(r.note)
    if (r.pending) Text("Waiting to sync", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.vote(r, true) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = RwColors.Green)) {
            Icon(Icons.Filled.ThumbUp, null); Spacer(Modifier.width(6.dp)); Text("Still there")
        }
        OutlinedButton(onClick = { vm.vote(r, false) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.ThumbDown, null); Spacer(Modifier.width(6.dp)); Text("Not there")
        }
    }
    if (r.mine) TextButton(onClick = { vm.deleteReport(r) }) { Text("Delete my report", color = MaterialTheme.colorScheme.error) }
}

@Composable
fun ReportPicker(vm: MainViewModel) {
    Dialog(onDismissRequest = { vm.openReportPicker(false) }) {
        Surface(shape = RoundedCornerShape(26.dp), tonalElevation = 4.dp) {
            Column(Modifier.padding(18.dp)) {
                Text("What do you see?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Reports are placed at your current location", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                LazyVerticalGrid(GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ReportType.entries) { t ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.submitReport(t) }) {
                            Surface(shape = CircleShape, color = Color(t.color.toInt()), modifier = Modifier.size(64.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(t.emoji, fontSize = 30.sp) }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(t.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.openReportPicker(false) }, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}
