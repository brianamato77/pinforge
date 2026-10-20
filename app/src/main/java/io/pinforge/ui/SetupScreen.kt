package io.pinforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import io.pinforge.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: AppViewModel) {
    BackHandler { vm.showSetup = false }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Toolchain runtime") },
            navigationIcon = { IconButton(onClick = { vm.showSetup = false }) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (vm.runtimeReady) "Installed: ${vm.rt.version()}" else "Not installed",
                style = MaterialTheme.typography.titleMedium,
                color = if (vm.runtimeReady) Teal else Amber
            )
            Text(
                "PinForge compiles on this phone. The runtime bundles arduino-cli, a glibc loader, bash and " +
                    "python3. Board packages and libraries are downloaded from the Packages tab and their Linux " +
                    "toolchains are adapted to Android automatically.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                vm.runtimeUrl, { vm.runtimeUrl = it }, label = { Text("Runtime URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = Mono
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.installRuntime() }, enabled = vm.busy == null) {
                    Text(if (vm.runtimeReady) "Reinstall" else "Install")
                }
                OutlinedButton(onClick = { vm.selfTest() }, enabled = vm.busy == null && vm.runtimeReady) { Text("Self-test") }
                OutlinedButton(onClick = { vm.repairToolchains() }, enabled = vm.busy == null && vm.runtimeReady) { Text("Re-patch") }
            }
            Text("Loader: ${vm.rt.interpPath}", style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LogPanel(vm.console, Modifier.fillMaxWidth().weight(1f))
        }
    }
}
