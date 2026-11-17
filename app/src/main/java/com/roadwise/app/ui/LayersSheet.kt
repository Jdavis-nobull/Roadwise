package com.roadwise.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadwise.app.core.ThemeMode

/** Map type picker: Default or Satellite, plus day/night. */
@Composable
fun LayersSheet(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = { vm.openLayers(false) }) {
        Surface(shape = RoundedCornerShape(26.dp), tonalElevation = 4.dp) {
            Column(Modifier.padding(18.dp)) {
                Text("Map type", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MapTypeCard("Default", Icons.Filled.Map, !s.satellite,
                        Brush.linearGradient(listOf(Color(0xFFE8EAED), Color(0xFFC8E6C9), Color(0xFFBBDEFB))), Modifier.weight(1f)) {
                        vm.setSatellite(false)
                    }
                    MapTypeCard("Satellite", Icons.Filled.Satellite, s.satellite,
                        Brush.linearGradient(listOf(Color(0xFF2E4D2C), Color(0xFF6B6B4E), Color(0xFF3B5A6B))), Modifier.weight(1f)) {
                        vm.setSatellite(true)
                    }
                }
                if (s.satellite) {
                    Text(
                        if (online) "Satellite photos by Esri (high-resolution aerial imagery), with street names on top."
                        else "Satellite photos need internet. Areas you've already viewed still show.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("Light", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(ThemeMode.AUTO to "Auto", ThemeMode.DAY to "Day", ThemeMode.NIGHT to "Night").forEachIndexed { i, (m, label) ->
                        SegmentedButton(selected = s.theme == m, onClick = { vm.updateSettings { it.copy(theme = m) } },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                    }
                }
                TextButton(onClick = { vm.openLayers(false) }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun MapTypeCard(label: String, icon: ImageVector, selected: Boolean, preview: Brush, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().height(84.dp),
        ) {
            Box(Modifier.fillMaxSize().background(preview), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
