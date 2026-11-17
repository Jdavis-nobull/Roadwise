package com.roadwise.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadwise.app.core.RoutingPreference
import com.roadwise.app.core.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.reportStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(s.reportServer) }
    var nick by remember { mutableStateOf(s.nickname) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                vm.updateSettings { it.copy(reportServer = server, nickname = nick.ifBlank { "Driver" }) }
                vm.openScreen(Screen.MAP)
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Header("Navigation")
            Toggle("Voice guidance", "Spoken turn-by-turn directions", s.voice) { v -> vm.updateSettings { it.copy(voice = v) } }
            Toggle("Use miles", "Off = kilometers", s.imperial) { v -> vm.updateSettings { it.copy(imperial = v) } }
            Toggle("3D driving view", "Tilt the map while navigating", s.tilt3d) { v -> vm.updateSettings { it.copy(tilt3d = v) } }
            Toggle("Keep screen on", "While navigating", s.keepScreenOn) { v -> vm.updateSettings { it.copy(keepScreenOn = v) } }

            Header("Route options")
            Toggle("Avoid tolls", null, s.avoidTolls) { v -> vm.updateSettings { it.copy(avoidTolls = v) } }
            Toggle("Avoid highways", null, s.avoidHighways) { v -> vm.updateSettings { it.copy(avoidHighways = v) } }
            Toggle("Avoid ferries", null, s.avoidFerries) { v -> vm.updateSettings { it.copy(avoidFerries = v) } }
            Text("Routing source", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            Choice(listOf(
                RoutingPreference.AUTO to "Automatic - online when possible, offline otherwise",
                RoutingPreference.OFFLINE_ONLY to "Always offline (uses downloaded areas, saves data)",
                RoutingPreference.ONLINE_ONLY to "Online only",
            ), s.routing) { v -> vm.updateSettings { it.copy(routing = v) } }

            Header("Alerts")
            Toggle("Speed limit warning", "Chime when over the limit", s.speedAlert) { v -> vm.updateSettings { it.copy(speedAlert = v) } }
            Text("Warn when over by ${s.speedTolerance} ${if (s.imperial) "mph" else "km/h"}", fontSize = 14.sp)
            Slider(s.speedTolerance.toFloat(), { v -> vm.updateSettings { it.copy(speedTolerance = v.toInt()) } }, valueRange = 0f..15f, steps = 14)
            Toggle("Hazard & police alerts", "Warn about reports on your route", s.reportAlerts) { v -> vm.updateSettings { it.copy(reportAlerts = v) } }
            Toggle("Speed camera alerts", "From OpenStreetMap camera data", s.cameraAlerts) { v -> vm.updateSettings { it.copy(cameraAlerts = v) } }

            Header("Map")
            Toggle("Satellite view", "Aerial photos with street names (needs internet)", s.satellite) { v -> vm.updateSettings { it.copy(satellite = v) } }
            Choice(listOf(ThemeMode.AUTO to "Day/night automatic (sunset)", ThemeMode.DAY to "Always day", ThemeMode.NIGHT to "Always night"), s.theme) { v ->
                vm.updateSettings { it.copy(theme = v) }
            }

            Header("Live reports (community)")
            Text("Reports you make are always kept on this phone. To share them live with family and friends, point everyone's app at the same Roadwise report server.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(nick, { nick = it }, label = { Text("Your name on reports") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(server, { server = it }, label = { Text("Report server URL") }, placeholder = { Text("https://...") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    vm.updateSettings { it.copy(reportServer = server, nickname = nick.ifBlank { "Driver" }) }
                    scope.launch { testResult = if (server.isBlank()) "No server set" else vm.testServer(server) }
                }) { Text("Save & test") }
                Text(testResult ?: status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Header("About")
            Text("Roadwise 1.0 · Map data © OpenStreetMap contributors. Map tiles: OpenFreeMap. Online routing: Valhalla (FOSSGIS). Search: Photon by Komoot. Offline routing: BRouter. Places & cameras: Overpass API.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Live traffic speeds are not available; traffic comes from community jam reports.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Header(t: String) {
    Text(t, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub != null) Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        options.forEach { (v, label) ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(v) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == v, { onSelect(v) })
                Text(label, fontSize = 14.sp)
            }
        }
    }
}
