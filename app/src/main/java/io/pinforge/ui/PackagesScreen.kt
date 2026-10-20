package io.pinforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.pinforge.AppViewModel
import io.pinforge.model.Boards
import io.pinforge.model.CodeGen

@Composable
fun PackagesScreen(vm: AppViewModel) {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub) {
            Tab(sub == 0, { sub = 0 }, text = { Text("Libraries") })
            Tab(sub == 1, { sub = 1 }, text = { Text("Boards") })
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (sub == 0) LibrariesPane(vm) else BoardsPane(vm)
        }
        LogPanel(vm.console, Modifier.fillMaxWidth().height(140.dp))
    }
}

@Composable
private fun LibrariesPane(vm: AppViewModel) {
    var q by remember { mutableStateOf("") }
    val idle = vm.busy == null
    val needed = if (vm.project != null) CodeGen.libraries(vm.board, vm.wiring) else emptyList()
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            q, { q = it }, label = { Text("Search Arduino libraries") }, singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) vm.searchLibs(q) })
        )
        Button(onClick = { vm.searchLibs(q) }, enabled = idle && q.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) { Text("Go") }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (needed.isNotEmpty()) {
            item { SectionTitle("Needed by this wiring") }
            items(needed) { name ->
                LibRow(name, vm.installedLibs[name], null, idle) { vm.installLib(name) }
            }
        }
        if (vm.libResults.isNotEmpty()) {
            item { SectionTitle("Results") }
            items(vm.libResults) { l ->
                LibRow(l.name, vm.installedLibs[l.name], "${l.version} · ${l.author}\n${l.sentence}", idle) { vm.installLib(l.name) }
            }
        }
        item { SectionTitle("Installed (${vm.installedLibs.size})") }
        items(vm.installedLibs.entries.sortedBy { it.key.lowercase() }) { e ->
            Text("${e.key}  ${e.value}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun LibRow(name: String, installed: String?, detail: String?, enabled: Boolean, onInstall: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            if (installed != null) Text("✓ $installed", color = Teal, style = MaterialTheme.typography.labelMedium)
            else FilledTonalButton(onClick = onInstall, enabled = enabled) { Text("Install") }
        }
    }
}

@Composable
private fun BoardsPane(vm: AppViewModel) {
    val idle = vm.busy == null
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.updateIndex() }, enabled = idle) { Text("Update index") }
                OutlinedButton(onClick = { vm.refreshPackages() }, enabled = idle) { Text("Refresh") }
            }
        }
        items(Boards.cores) { c ->
            val v = vm.installedCores[c.id]
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text("${c.id} · ${c.sizeHint}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val boards = Boards.all.filter { it.core == c.id }.joinToString { it.name }
                        Text(boards, style = MaterialTheme.typography.bodySmall)
                    }
                    if (v != null) Column(horizontalAlignment = Alignment.End) {
                        Text("✓ $v", color = Teal, style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick = { vm.installCore(c.id) }, enabled = idle) { Text("Update") }
                    } else Button(onClick = { vm.installCore(c.id) }, enabled = idle) { Text("Install") }
                }
            }
        }
        item {
            Text(
                "Board packages are large; the first ESP32 install can take a while on mobile data. " +
                    "Compiling auto-installs the package for the selected board if it's missing.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
