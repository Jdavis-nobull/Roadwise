package com.roadwise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadwise.app.core.Units
import com.roadwise.app.nav.NavState
import com.roadwise.app.routing.Turn
import java.text.DateFormat
import java.util.Date

fun turnIcon(t: Turn): ImageVector = when (t) {
    Turn.DEPART -> Icons.Filled.Navigation
    Turn.STRAIGHT -> Icons.Filled.Straight
    Turn.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
    Turn.LEFT -> Icons.Filled.TurnLeft
    Turn.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    Turn.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    Turn.RIGHT -> Icons.Filled.TurnRight
    Turn.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    Turn.UTURN -> Icons.Filled.UTurnLeft
    Turn.KEEP_LEFT -> Icons.Filled.ForkLeft
    Turn.KEEP_RIGHT -> Icons.Filled.ForkRight
    Turn.EXIT_LEFT, Turn.RAMP_LEFT -> Icons.Filled.RampLeft
    Turn.EXIT_RIGHT, Turn.RAMP_RIGHT -> Icons.Filled.RampRight
    Turn.MERGE -> Icons.Filled.Merge
    Turn.ROUNDABOUT -> Icons.Filled.RoundaboutRight
    Turn.FERRY -> Icons.Filled.DirectionsBoat
    Turn.ARRIVE -> Icons.Filled.Flag
}

@Composable
fun NavHud(vm: MainViewModel, s: NavState, ui: UiState) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val simulating by vm.simulating.collectAsStateWithLifecycle()
    val imperial = settings.imperial
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        // ---------- top: maneuver banner ----------
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp)) {
            val next = s.next
            if (s.arrived) {
                Banner(Icons.Filled.Flag, "Arrived", s.destination?.name ?: "Destination", null)
            } else if (s.rerouting) {
                Banner(Icons.Filled.Autorenew, "Rerouting", "Finding a new route...", null)
            } else if (next != null) {
                Banner(turnIcon(next.turn), Units.distance(s.distToNextM, imperial), next.text, s.then?.let { "Then " + it.text.replaceFirstChar { c -> c.lowercase() } }, next.turn)
            }
            s.message?.let { msg ->
                Spacer(Modifier.height(6.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF37474F), contentColor = Color.White, onClick = { vm.c.nav.clearMessage() }) {
                    Text(msg, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp)
                }
            }
            s.alert?.let { a ->
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.emoji, fontSize = 32.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(a.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        a.report?.let { r ->
                            IconButton(onClick = { vm.vote(r, true) }) { Icon(Icons.Filled.ThumbUp, "Still there", tint = RwColors.Green) }
                            IconButton(onClick = { vm.vote(r, false) }) { Icon(Icons.Filled.ThumbDown, "Not there", tint = RwColors.Red) }
                        } ?: IconButton(onClick = { vm.c.nav.dismissAlert() }) { Icon(Icons.Filled.Close, "Dismiss") }
                    }
                }
            }
            if (simulating) {
                Spacer(Modifier.height(6.dp))
                AssistChip(onClick = {}, label = { Text("Preview drive (simulated GPS)") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface))
            }
        }

        // ---------- left: speedometer + limit ----------
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 120.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.speedLimitKmh > 0) LimitSign(Units.limitFromKmh(s.speedLimitKmh, imperial), imperial)
            SpeedBadge(speedLabel(s.speedMps, imperial), Units.speedUnit(imperial), s.overspeed)
        }

        // ---------- right: controls ----------
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallFloatingActionButton(onClick = { vm.updateSettings { it.copy(voice = !it.voice) } }, containerColor = MaterialTheme.colorScheme.surface) {
                Icon(if (settings.voice) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "Voice")
            }
            val live by vm.liveShare.collectAsStateWithLifecycle()
            SmallFloatingActionButton(onClick = { vm.openShareDialog(true) },
                containerColor = if (live.active) RwColors.Red else MaterialTheme.colorScheme.surface,
                contentColor = if (live.active) Color.White else MaterialTheme.colorScheme.onSurface) {
                Icon(Icons.Filled.ShareLocation, "Share trip")
            }
            SmallFloatingActionButton(onClick = { vm.overview() }, containerColor = MaterialTheme.colorScheme.surface) {
                Icon(Icons.Filled.Route, "Route overview")
            }
            FloatingActionButton(onClick = { vm.openReportPicker(true) }, containerColor = RwColors.Orange, contentColor = Color.White, shape = CircleShape) {
                Icon(Icons.Filled.Campaign, "Report")
            }
        }

        if (!ui.following) {
            ExtendedFloatingActionButton(onClick = { vm.recenter() }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp),
                icon = { Icon(Icons.Filled.Navigation, null) }, text = { Text("Re-center") })
        }

        // ---------- bottom: trip summary ----------
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(24.dp), shadowElevation = 12.dp) {
            if (s.arrived) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🏁", fontSize = 30.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("You've arrived", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(s.destination?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { vm.endNavigation() }) { Text("Done") }
                }
            } else {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.endNavigation() }, modifier = Modifier.size(48.dp).background(RwColors.Red.copy(alpha = 0.12f), CircleShape)) {
                        Icon(Icons.Filled.Close, "End navigation", tint = RwColors.Red)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Units.duration(s.remainingS), fontWeight = FontWeight.Bold, fontSize = 24.sp, color = RwColors.Green)
                        Text("${Units.distance(s.remainingM, imperial)} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.etaMillis))}",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.currentStreet?.let { Text(it, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
    }
}

@Composable
private fun Banner(icon: ImageVector, big: String, text: String, then: String?, turn: Turn? = null) {
    Surface(shape = RoundedCornerShape(20.dp), color = RwColors.NavGreen, contentColor = Color.White, shadowElevation = 10.dp) {
        Column {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(big, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(text, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                }
            }
            if (then != null) {
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.18f)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(then, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
