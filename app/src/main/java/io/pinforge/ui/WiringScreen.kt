package io.pinforge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pinforge.AppViewModel
import io.pinforge.model.Board
import io.pinforge.model.CodeGen
import io.pinforge.model.Parts
import io.pinforge.model.Pin
import io.pinforge.model.Placed
import io.pinforge.model.Role
import io.pinforge.model.Wiring

// ---- geometry (world units = dp at zoom 1) ----
private const val BW = 150f      // board width
private const val BTOP = 34f     // first pin offset
private const val BP = 22f       // board pin pitch
private const val PW = 128f      // part width
private const val PTOP = 26f
private const val PP = 20f       // part pin pitch

private fun boardHeight(b: Board) = BTOP + maxOf(b.left.size, b.right.size) * BP + 8f
private fun boardPinPos(key: String): Offset {
    val i = key.drop(1).toIntOrNull() ?: 0
    val x = if (key.startsWith("L")) 0f else BW
    return Offset(x, BTOP + i * BP + BP / 2)
}
private fun partHeight(n: Int) = PTOP + n.coerceAtLeast(1) * PP + 6f
private fun partFacesLeft(p: Placed) = p.x + PW / 2 > BW / 2     // part sits right of board → pins on its left edge
private fun partPinPos(p: Placed, j: Int): Offset =
    Offset(if (partFacesLeft(p)) p.x else p.x + PW, p.y + PTOP + j * PP + PP / 2)

private fun roleColor(r: Role): Color = when (r) {
    Role.VCC -> Color(0xFFE5534B)
    Role.GND -> Color(0xFF8B98A5)
    Role.SDA -> Color(0xFF58A6FF)
    Role.SCL -> Color(0xFFF0883E)
    Role.MOSI, Role.MISO, Role.SCK, Role.CS -> Color(0xFFBC8CFF)
    Role.ANALOG -> Color(0xFF3FB950)
    Role.SIG -> Color(0xFFE3B341)
}

private fun pinColor(p: Pin): Color = when {
    p.isPower -> Color(0xFFE5534B)
    p.isGnd -> Color(0xFF8B98A5)
    p.has("NC") || p.code == null -> Color(0xFF3A434E)
    p.has("X") -> Color(0xFFDB6D28)
    p.has("A") -> Color(0xFF3FB950)
    else -> Color(0xFFE3B341)
}

private class Hit(val partPin: Pair<String, String>? = null, val boardKey: String? = null, val part: String? = null)

private fun hitTest(board: Board, w: Wiring, pt: Offset, tol: Float): Hit {
    for (pl in w.parts) {
        val t = Parts.byId(pl.type) ?: continue
        t.pins.forEachIndexed { j, cp ->
            if ((partPinPos(pl, j) - pt).getDistance() < tol) return Hit(partPin = pl.id to cp.name)
        }
    }
    for ((k, _) in board.keyed()) {
        if ((boardPinPos(k) - pt).getDistance() < tol) return Hit(boardKey = k)
    }
    for (pl in w.parts.asReversed()) {
        val n = Parts.byId(pl.type)?.pins?.size ?: 0
        if (pt.x in pl.x..(pl.x + PW) && pt.y in pl.y..(pl.y + partHeight(n))) return Hit(part = pl.id)
    }
    return Hit()
}

@Composable
fun WiringScreen(vm: AppViewModel) {
    if (vm.project == null) { NoProject(vm); return }
    val board = vm.board
    val density = LocalDensity.current.density
    val tm = rememberTextMeasurer()
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset(Float.NaN, 24f)) }
    var selPin by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selPart by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Tap a part's pin, then a board pin to wire it. Drag parts to move. Long-press a part pin to unwire.") }
    var showAdd by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    val issues = remember(board, vm.wiring) { CodeGen.check(board, vm.wiring) }
    val unit = density * zoom
    fun toWorld(o: Offset) = Offset((o.x - pan.x) / (density * zoom), (o.y - pan.y) / (density * zoom))

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(onClick = { showAdd = true }) { Text("+ Part") }
            FilledTonalButton(onClick = { vm.autoWire(); status = "Auto-wired unconnected pins — check the warnings." }) { Text("Auto-wire") }
            FilledTonalButton(onClick = { showCode = true }) { Text("Generate code") }
            OutlinedButton(onClick = { showIssues = true }) {
                Text(if (issues.isEmpty()) "✓ No issues" else "⚠ ${issues.size} issue${if (issues.size == 1) "" else "s"}",
                    color = if (issues.isEmpty()) Teal else Amber)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFF0B0F14))
            .onSizeChanged { sz -> if (pan.x.isNaN()) pan = Offset(sz.width / 2f - BW * unit / 2f, 24f) }) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(board) {
                        detectTapGestures(
                            onTap = { pos ->
                                val h = hitTest(board, vm.wiring, toWorld(pos), 14f / zoom)
                                val sp = selPin
                                when {
                                    h.partPin != null -> {
                                        selPin = h.partPin; selPart = h.partPin.first
                                        status = "${h.partPin.first}.${h.partPin.second} selected → tap a board pin"
                                    }
                                    h.boardKey != null -> {
                                        val pin = board.pin(h.boardKey)
                                        if (sp != null && pin != null) {
                                            vm.connect(sp.first, sp.second, h.boardKey)
                                            status = "Wired ${sp.first}.${sp.second} → ${pin.label}"
                                            selPin = null
                                        } else if (pin != null) {
                                            status = "${pin.label}${pin.code?.let { c -> " (code: $c)" } ?: ""} · ${describeCaps(pin)}"
                                        }
                                    }
                                    h.part != null -> {
                                        selPart = h.part; selPin = null
                                        val t = Parts.byId(vm.wiring.parts.first { it.id == h.part }.type)
                                        status = "${h.part}: ${t?.desc ?: ""}"
                                    }
                                    else -> { selPin = null; selPart = null }
                                }
                            },
                            onLongPress = { pos ->
                                val h = hitTest(board, vm.wiring, toWorld(pos), 14f / zoom)
                                h.partPin?.let { vm.disconnect(it.first, it.second); status = "Unwired ${it.first}.${it.second}" }
                            }
                        )
                    }
                    .pointerInput(board) {
                        detectDragGestures(
                            onDragStart = { pos -> dragging = hitTest(board, vm.wiring, toWorld(pos), 14f / zoom).part },
                            onDragEnd = { if (dragging != null) vm.setWiring(vm.wiring); dragging = null },
                            onDragCancel = { dragging = null }
                        ) { change, amount ->
                            change.consume()
                            val d = dragging
                            if (d != null) vm.movePart(d, amount.x / (density * zoom), amount.y / (density * zoom))
                            else pan += amount
                        }
                    }
            ) {
                translate(pan.x, pan.y) {
                    scale(unit, unit, pivot = Offset.Zero) {
                        drawBoard(board, tm, density)
                        drawParts(vm.wiring, tm, density, selPart, selPin)
                        drawWires(board, vm.wiring, selPin)
                    }
                }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallFloatingActionButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }) { Text("+") }
                SmallFloatingActionButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.3f) }) { Text("−") }
                SmallFloatingActionButton(onClick = { zoom = 1f; pan = Offset(Float.NaN, 24f) }) { Text("⌂") }
            }
        }

        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(status, style = MaterialTheme.typography.bodySmall)
            val sp = selPart
            if (sp != null && vm.wiring.parts.any { it.id == sp }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.removePart(sp); selPart = null; selPin = null }) {
                        Text("Delete $sp", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showAdd) AddPartDialog(board, onDismiss = { showAdd = false }) { id ->
        vm.addPart(id); showAdd = false
        status = "Added — tap its pins to wire, or use Auto-wire."
    }
    if (showCode) GenerateDialog(vm) { showCode = false }
    if (showIssues) AlertDialog(
        onDismissRequest = { showIssues = false },
        title = { Text("Wiring check") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (issues.isEmpty()) item { Text("Everything is connected and compatible.") }
                items(issues) { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = { showIssues = false }) { Text("OK") } }
    )
}

private fun describeCaps(p: Pin): String {
    val m = mapOf(
        "D" to "digital", "IN" to "input-only", "A" to "analog", "P" to "PWM", "SDA" to "I²C SDA", "SCL" to "I²C SCL",
        "MOSI" to "SPI MOSI", "MISO" to "SPI MISO", "SCK" to "SPI SCK", "RX" to "UART RX", "TX" to "UART TX",
        "X" to "avoid (boot/USB/serial)", "PWR3" to "3.3V out", "PWR5" to "5V", "GND" to "ground", "NC" to "not usable"
    )
    return p.caps.mapNotNull { m[it] }.joinToString(", ")
}

private fun label(tm: TextMeasurer, text: String, density: Float, size: Float = 10f, color: Color = Color(0xFFC9D1D9), bold: Boolean = false) =
    tm.measure(
        text,
        TextStyle(
            color = color, fontSize = (size / density).sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    )

private fun DrawScope.drawBoard(b: Board, tm: TextMeasurer, density: Float) {
    val h = boardHeight(b)
    drawRoundRect(Color(0xFF14432F), Offset.Zero, Size(BW, h), CornerRadius(8f, 8f))
    drawRoundRect(Color(0xFF2EA043), Offset.Zero, Size(BW, h), CornerRadius(8f, 8f), style = Stroke(1.5f))
    val title = label(tm, b.name, density, 10f, Color.White, bold = true)
    drawText(title, topLeft = Offset(((BW - title.size.width) / 2).coerceAtLeast(4f), 8f))
    // chip in the middle
    drawRoundRect(Color(0xFF0D1117), Offset(BW / 2 - 22f, h / 2 - 22f), Size(44f, 44f), CornerRadius(3f, 3f))
    b.left.forEachIndexed { i, p ->
        val pos = boardPinPos("L$i")
        drawCircle(pinColor(p), 5f, pos)
        val t = label(tm, p.label, density, 9f, pinColor(p).copy(alpha = 0.95f))
        drawText(t, topLeft = Offset(pos.x + 9f, pos.y - t.size.height / 2f))
    }
    b.right.forEachIndexed { i, p ->
        val pos = boardPinPos("R$i")
        drawCircle(pinColor(p), 5f, pos)
        val t = label(tm, p.label, density, 9f, pinColor(p).copy(alpha = 0.95f))
        drawText(t, topLeft = Offset(pos.x - 9f - t.size.width, pos.y - t.size.height / 2f))
    }
}

private fun DrawScope.drawParts(w: Wiring, tm: TextMeasurer, density: Float, sel: String?, selPin: Pair<String, String>?) {
    for (pl in w.parts) {
        val t = Parts.byId(pl.type) ?: continue
        val h = partHeight(t.pins.size)
        val selected = pl.id == sel
        drawRoundRect(Color(0xFF1C2430), Offset(pl.x, pl.y), Size(PW, h), CornerRadius(6f, 6f))
        drawRoundRect(if (selected) Teal else Color(0xFF3A4654), Offset(pl.x, pl.y), Size(PW, h), CornerRadius(6f, 6f),
            style = Stroke(if (selected) 2.5f else 1.2f))
        val name = tm.measure(
            "${pl.id} · ${t.name}",
            TextStyle(color = Color.White, fontSize = (9f / density).sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = (PW - 12f).toInt())
        )
        drawText(name, topLeft = Offset(pl.x + 6f, pl.y + 6f))
        val left = partFacesLeft(pl)
        t.pins.forEachIndexed { j, cp ->
            val pos = partPinPos(pl, j)
            val isSel = selPin != null && selPin.first == pl.id && selPin.second == cp.name
            drawCircle(roleColor(cp.role), if (isSel) 7f else 5f, pos)
            if (isSel) drawCircle(Color.White, 9f, pos, style = Stroke(1.5f))
            val lt = label(tm, cp.name, density, 9f, roleColor(cp.role))
            val x = if (left) pos.x + 9f else pos.x - 9f - lt.size.width
            drawText(lt, topLeft = Offset(x, pos.y - lt.size.height / 2f))
        }
    }
}

private fun DrawScope.drawWires(b: Board, w: Wiring, selPin: Pair<String, String>?) {
    for (wr in w.wires) {
        val pl = w.parts.firstOrNull { it.id == wr.part } ?: continue
        val t = Parts.byId(pl.type) ?: continue
        val j = t.pins.indexOfFirst { it.name == wr.pin }
        if (j < 0 || b.pin(wr.board) == null) continue
        val a = partPinPos(pl, j)
        val z = boardPinPos(wr.board)
        val dirA = if (partFacesLeft(pl)) -1f else 1f
        val dirZ = if (wr.board.startsWith("L")) -1f else 1f
        val bend = maxOf(40f, kotlin.math.abs(a.x - z.x) / 2)
        val path = Path().apply {
            moveTo(a.x, a.y)
            cubicTo(a.x + dirA * bend, a.y, z.x + dirZ * bend, z.y, z.x, z.y)
        }
        val hl = selPin != null && selPin.first == wr.part && selPin.second == wr.pin
        drawPath(path, roleColor(t.pins[j].role).copy(alpha = if (hl) 1f else 0.85f), style = Stroke(if (hl) 3.5f else 2.2f, cap = StrokeCap.Round))
    }
}

@Composable
private fun AddPartDialog(board: Board, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a part") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(Parts.all.filter { it.archOnly == null || board.arch in it.archOnly }) { t ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(t.id) }.padding(vertical = 8.dp)) {
                        Text(t.name, style = MaterialTheme.typography.bodyLarge)
                        Text(t.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val libs = t.libs(board.arch)
                        if (libs.isNotEmpty()) Text("libs: ${libs.joinToString()}", style = MaterialTheme.typography.labelSmall, color = Teal)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun GenerateDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val libs = CodeGen.libraries(vm.board, vm.wiring)
    val missing = libs.filter { it !in vm.installedLibs }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("wiring.h gets a #define for every connected pin (e.g. LED1_SIG), plus I²C/SPI helpers.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.writeHeader(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Update wiring.h only")
                }
                Button(onClick = { vm.applyStarter(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Replace sketch with starter code")
                }
                FilledTonalButton(onClick = { vm.installPartLibs(); onDismiss() }, enabled = missing.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (missing.isEmpty()) "Libraries installed (${libs.size})" else "Install ${missing.size} librar${if (missing.size == 1) "y" else "ies"}")
                }
                if (missing.isNotEmpty()) Text(missing.joinToString(), style = MaterialTheme.typography.labelSmall, color = Amber)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

