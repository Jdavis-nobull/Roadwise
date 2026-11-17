package com.roadwise.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import com.roadwise.app.core.OfflineArea
import com.roadwise.app.offline.Region
import com.roadwise.app.offline.US_STATES
import java.text.DateFormat
import java.util.Date

@Composable
fun OfflineScreen(vm: MainViewModel) {
    val areas by vm.areas.collectAsStateWithLifecycle()
    var used by remember { mutableLongStateOf(0L) }
    LaunchedEffect(areas) { used = vm.storageUsed() }
    var confirmDelete by remember { mutableStateOf<OfflineArea?>(null) }
    var pickStates by remember { mutableStateOf(false) }
    if (pickStates) StatePicker(vm, areas.map { it.name }.toSet()) { pickStates = false }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openScreen(Screen.MAP) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Offline maps", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Use Roadwise with no signal", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Each download includes the map (day and night), turn-by-turn routing data, a search index of places and streets, and speed cameras. GPS and voice guidance work without data.",
                            fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        val mine = remember { vm.myState() }
                        if (mine != null && areas.none { it.name == mine.name }) {
                            val est = remember(mine) { vm.estimateRegion(mine) }
                            Button(onClick = { vm.downloadRegions(listOf(mine)) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Download all of ${mine.name}", fontWeight = FontWeight.Bold)
                                    Text("about ${mb(est.mapMb + est.routingMbGuess)} · one tap, runs in background", fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickStates = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Checklist, null); Spacer(Modifier.width(6.dp)); Text("Choose states")
                            }
                            OutlinedButton(onClick = { vm.beginAreaSelect() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.AddLocationAlt, null); Spacer(Modifier.width(6.dp)); Text("Pick on map")
                            }
                        }
                        Text("Tip: use Wi-Fi. Downloads keep going with the screen off.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("Storage used: ${mb(used / 1_048_576.0)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (areas.isEmpty()) item {
                Text("No offline areas yet. Tip: download your home state over Wi-Fi.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp))
            }
            items(areas.filter { it.status != "cancelled" }, key = { it.id }) { a ->
                AreaCard(a, onDelete = { confirmDelete = a }, onRetry = { vm.retryArea(a) }, onCancel = { vm.cancelDownload(a) })
            }
        }
    }

    confirmDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${a.name}?") },
            text = { Text("The offline map, routing data and search index for this area will be removed from the phone. You can download it again later.") },
            confirmButton = { TextButton(onClick = { vm.deleteArea(a); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatePicker(vm: MainViewModel, have: Set<String>, onClose: () -> Unit) {
    val selected = remember { mutableStateListOf<Region>() }
    val estimates = remember { US_STATES.associateWith { vm.estimateRegion(it) } }
    val total = selected.sumOf { estimates[it]!!.mapMb } + selected.flatMap { estimates[it]!!.routingFiles }.distinct().size * 120.0
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Download states") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected.clear(); selected.addAll(US_STATES.filter { it.name !in have }) }) { Text("Select all") }
                    TextButton(onClick = { selected.clear() }) { Text("Clear") }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(US_STATES) { s ->
                        val done = s.name in have
                        Row(Modifier.fillMaxWidth().clickable(enabled = !done) { if (s in selected) selected.remove(s) else selected.add(s) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = done || s in selected, onCheckedChange = null, enabled = !done)
                            Text(s.name, Modifier.weight(1f))
                            Text(if (done) "downloaded" else "~${mb(estimates[s]!!.mapMb)}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (selected.isNotEmpty()) Text("Total about ${mb(total)} (map + routing). Make sure the phone has room.",
                    fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { vm.downloadRegions(selected.toList()); onClose() }) {
                Text(if (selected.size <= 1) "Download" else "Download ${selected.size} states")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun AreaCard(a: OfflineArea, onDelete: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (a.status) { "ready" -> Icons.Filled.CheckCircle; "error" -> Icons.Filled.Error; else -> Icons.Filled.Downloading },
                    null, tint = when (a.status) { "ready" -> RwColors.Green; "error" -> RwColors.Red; else -> MaterialTheme.colorScheme.primary },
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        when (a.status) {
                            "ready" -> "${mb(a.mapBytes / 1_048_576.0)} map · ${"%,d".format(a.pois)} places · ${a.cameras} cameras"
                            "error" -> "Failed: ${a.error ?: "unknown error"}"
                            "queued" -> "Waiting to download"
                            else -> a.step
                        },
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (a.status == "ready") Text("Downloaded ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.created))}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (a.status == "error") IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, "Retry") }
                if (a.status == "ready") IconButton(onClick = onRetry) { Icon(Icons.Filled.Update, "Update") }
                if (a.status == "downloading" || a.status == "queued") {
                    TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
                } else {
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
            if (a.status == "downloading" || a.status == "queued") {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { a.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
