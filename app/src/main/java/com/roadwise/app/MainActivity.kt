package com.roadwise.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.roadwise.app.ui.MainViewModel
import com.roadwise.app.ui.RoadwiseRoot
import com.roadwise.app.ui.Screen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermissionsResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RoadwiseRoot(vm, onExit = { finish() }) }

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
        handleIntent(intent)

        // Keep the screen awake while navigating (if enabled in settings).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.nav, vm.settings) { n, s -> n.active && s.keepScreenOn }.collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getStringExtra("open") == "offline") vm.openScreen(Screen.OFFLINE)
        // geo: links from other apps ("geo:lat,lon?q=...")
        i?.data?.takeIf { it.scheme == "geo" }?.let { uri ->
            val ssp = uri.schemeSpecificPart ?: return@let
            val coords = ssp.substringBefore('?').split(',')
            val q = uri.getQueryParameter("q")
            val lat = coords.getOrNull(0)?.toDoubleOrNull(); val lon = coords.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                vm.selectPlace(com.roadwise.app.core.Place(q?.substringAfter('(')?.substringBefore(')') ?: "Shared location", "%.5f, %.5f".format(lat, lon), lat, lon, "pin", "link"))
            } else if (!q.isNullOrBlank()) {
                vm.openScreen(Screen.SEARCH); vm.setQuery(q)
            }
        }
    }

    override fun onResume() { super.onResume(); vm.onResume() }
    override fun onPause() { super.onPause(); vm.onPause() }
}
