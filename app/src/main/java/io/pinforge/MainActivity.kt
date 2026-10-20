package io.pinforge

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pinforge.ui.CodeScreen
import io.pinforge.ui.DeviceScreen
import io.pinforge.ui.PackagesScreen
import io.pinforge.ui.PinTheme
import io.pinforge.ui.ProjectsScreen
import io.pinforge.ui.SetupScreen
import io.pinforge.ui.WiringScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PinTheme { App(vm, this) } }
    }

    override fun onPause() {
        super.onPause()
        vm.saveAll()
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

private data class TabDef(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel, activity: MainActivity) {
    LaunchedEffect(vm.busy) { activity.keepScreenOn(vm.busy != null) }

    // UF2 boards show up as a USB drive; the user picks it once per upload.
    val uf2Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.writeUf2(uri)
    }
    LaunchedEffect(vm.pendingUf2) { if (vm.pendingUf2 != null) uf2Picker.launch(null) }

    if (vm.showSetup) {
        SetupScreen(vm)
        return
    }

    val tabs = listOf(
        TabDef("Projects", Icons.Default.Home),
        TabDef("Code", Icons.Default.Edit),
        TabDef("Wiring", Icons.Default.Share),
        TabDef("Packages", Icons.Default.List),
        TabDef("Device", Icons.Default.Build),
    )

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    title = {
                        Column {
                            Text(vm.project?.name ?: "PinForge", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (vm.project != null) Text(
                                vm.board.name, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        val b = vm.busy
                        if (b != null) {
                            Text(b, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            IconButton(onClick = { vm.cancel() }) { Icon(Icons.Default.Close, "Cancel") }
                        }
                        IconButton(onClick = { vm.showSetup = true }) { Icon(Icons.Default.Settings, "Toolchain setup") }
                    }
                )
                val p = vm.progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = vm.tab == i,
                        onClick = { vm.saveAll(); vm.tab = i },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, maxLines = 1) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.tab) {
                0 -> ProjectsScreen(vm)
                1 -> CodeScreen(vm)
                2 -> WiringScreen(vm)
                3 -> PackagesScreen(vm)
                else -> DeviceScreen(vm)
            }
        }
    }
}
