package com.roadwise.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
fun ShareDialog(vm: MainViewModel) {
    val ctx = LocalContext.current
    val live by vm.liveShare.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasServer = settings.reportServer.isNotBlank()

    Dialog(onDismissRequest = { vm.openShareDialog(false) }) {
        Surface(shape = RoundedCornerShape(26.dp), tonalElevation = 4.dp) {
            Column(Modifier.padding(18.dp)) {
                Text("Share location", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                if (live.active) {
                    Surface(shape = RoundedCornerShape(16.dp), color = RwColors.Red.copy(alpha = 0.10f)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).padding(0.dp)) { Surface(shape = CircleShape, color = RwColors.Red, modifier = Modifier.fillMaxSize()) {} }
                                Spacer(Modifier.width(8.dp))
                                Text("Sharing live", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                if (live.untilArrival) "Until you arrive"
                                else "Until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(live.expiresAt))}",
                                fontSize = 13.sp,
                            )
                            live.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                            Text(live.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { vm.copyLiveLink(ctx) }) { Text("Copy link") }
                                TextButton(onClick = { vm.stopLiveShare(); vm.openShareDialog(false) }) { Text("Stop sharing", color = RwColors.Red) }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                ShareRow(Icons.Filled.MyLocation, RwColors.Blue, "Send my current location", "A map link anyone can open") { vm.shareMyLocation(ctx) }

                if (nav.active) {
                    ShareRow(Icons.Filled.DirectionsCar, RwColors.Green, "Share trip & ETA",
                        if (hasServer) "Arrival time + live link until you arrive" else "Arrival time and where you are now") {
                        vm.shareTrip(ctx, live = hasServer)
                    }
                }

                if (!live.active) {
                    Text("Live location", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    if (hasServer) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15 to "15 min", 60 to "1 hour", 8 * 60 to "8 hours").forEach { (m, label) ->
                                OutlinedButton(onClick = { vm.startLiveShare(ctx, m) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                                    Text(label, fontSize = 13.sp)
                                }
                            }
                        }
                        Text("People with the link see you move on a map in their browser - no app needed.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    } else {
                        Text("Live sharing uses your Roadwise server. Add it in Settings → Live reports.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { vm.openShareDialog(false) }, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ShareRow(icon: ImageVector, color: Color, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
