package io.pinforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.pinforge.AppViewModel
import io.pinforge.model.Boards

@Composable
fun ProjectsScreen(vm: AppViewModel) {
    var showNew by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!vm.runtimeReady) item {
                Card(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clickable { vm.showSetup = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Install the toolchain runtime", style = MaterialTheme.typography.titleSmall)
                            Text("One-time ~60 MB download so this phone can compile sketches. Tap to set up.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { SectionTitle("Projects") }
            if (vm.projects.isEmpty()) item {
                Text("No projects yet. Tap New project to start.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(vm.projects) { name ->
                val open = vm.project?.name == name
                Card(
                    Modifier.fillMaxWidth().clickable { vm.open(name) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (open) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(Modifier.padding(start = 14.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(Boards.byId(vm.store.load(name).boardId).name, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { toDelete = name }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = { showNew = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("New project") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }

    if (showNew) NewProjectDialog(onDismiss = { showNew = false }) { name, board ->
        showNew = false
        vm.createProject(name, board)
    }

    toDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete $name?") },
            text = { Text("The sketch, wiring and build files are removed from this phone.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(name); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun BoardPicker(selected: String, onSelect: (String) -> Unit) {
    Column {
        Boards.all.forEach { b ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(b.id) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == b.id, onClick = { onSelect(b.id) })
                Column {
                    Text(b.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${b.fqbn} · ${b.volts}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("Blink") }
    var board by remember { mutableStateOf("esp32") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            LazyColumn {
                item {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item { SectionTitle("Board") }
                item { BoardPicker(board) { board = it } }
            }
        },
        confirmButton = { Button(onClick = { onCreate(name, board) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
