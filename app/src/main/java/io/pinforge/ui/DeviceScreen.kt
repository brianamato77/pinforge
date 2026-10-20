package io.pinforge.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.pinforge.AppViewModel

@Composable
fun DeviceScreen(vm: AppViewModel) {
    var refresh by remember { mutableIntStateOf(0) }
    val devices = remember(refresh) { vm.usb.devices() }
    var baudMenu by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.bleScan() }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ---------- USB ----------
            SectionTitle("USB (OTG cable)")
            if (devices.isEmpty()) Text("No USB device. Plug the board in with a USB-C OTG adapter.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            devices.forEach { Text("• " + vm.usb.describe(it), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { refresh++ }) { Text("Rescan") }
                Box {
                    FilledTonalButton(onClick = { baudMenu = true }, enabled = !vm.monitorOn) { Text("${vm.monitorBaud} baud") }
                    DropdownMenu(baudMenu, { baudMenu = false }) {
                        listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600).forEach { b ->
                            DropdownMenuItem(text = { Text("$b") }, onClick = { vm.monitorBaud = b; baudMenu = false })
                        }
                    }
                }
                if (vm.monitorOn) Button(onClick = { vm.stopMonitor() }) { Text("Disconnect") }
                else Button(onClick = { vm.startMonitor() }, enabled = devices.isNotEmpty()) { Text("Serial monitor") }
            }

            // ---------- Wi-Fi OTA ----------
            val p = vm.project
            if (p != null && vm.board.supportsOta) {
                SectionTitle("Wi-Fi OTA (ArduinoOTA)")
                Text("Add the \"Wi-Fi + OTA\" part on the Wiring tab, upload once over USB, then pick the board here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.discoverOta() }) { Text("Find boards") }
                    FilledTonalButton(onClick = { vm.setMethod(if (p.method == "ota") "usb" else "ota") }) {
                        Text(if (p.method == "ota") "Uploads: Wi-Fi ✓" else "Use Wi-Fi for uploads")
                    }
                }
                vm.otaHosts.forEach { h ->
                    Text("• ${h.name}  ${h.ip}", modifier = Modifier.fillMaxWidth().clickable { vm.setOtaHost(h.ip) }.padding(vertical = 6.dp),
                        color = if (p.otaHost == h.ip) Teal else MaterialTheme.colorScheme.onSurface)
                }
                OutlinedTextField(p.otaHost, { vm.setOtaHost(it.trim()) }, label = { Text("Board IP") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(vm.otaPassword, { vm.otaPassword = it }, label = { Text("OTA password (if set)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }

            // ---------- BLE ----------
            SectionTitle("Bluetooth LE (Nordic UART)")
            Text("Status: ${vm.bleState}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Scan") }
                if (vm.ble.connected) OutlinedButton(onClick = { vm.bleDisconnect() }) { Text("Disconnect") }
            }
            vm.bleDevices.sortedByDescending { it.rssi }.take(15).forEach { d ->
                Text("• ${d.name}  (${d.rssi} dBm)", modifier = Modifier.fillMaxWidth().clickable { vm.bleConnect(d) }.padding(vertical = 6.dp))
            }

            // ---------- UF2 ----------
            if (p != null && vm.board.uploader.name == "UF2") {
                SectionTitle("UF2 bootloader")
                Text("Upload reboots the board into its USB drive and asks you to pick it. If it doesn't appear, " +
                    "double-tap RESET (Pico: hold BOOTSEL while plugging in).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------- monitor ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Monitor", style = MaterialTheme.typography.titleSmall, color = Teal, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.monitor.clear() }) { Text("Clear") }
        }
        LogPanel(vm.monitor, Modifier.fillMaxWidth().height(200.dp))
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                line, { line = it }, singleLine = true, placeholder = { Text("Send to USB / BLE") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.sendSerial(line); line = "" })
            )
            Button(onClick = { vm.sendSerial(line); line = "" }, modifier = Modifier.padding(start = 8.dp)) { Text("Send") }
        }
    }
}
