package io.pinforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pinforge.AppViewModel

private object CppHighlight : VisualTransformation {
    private val kw = Regex("\\b(void|int|float|double|char|bool|byte|long|short|unsigned|signed|const|static|volatile|" +
        "uint8_t|uint16_t|uint32_t|int8_t|int16_t|int32_t|size_t|String|if|else|for|while|do|switch|case|default|" +
        "break|continue|return|true|false|HIGH|LOW|INPUT|OUTPUT|INPUT_PULLUP|struct|class|public|private|new|delete|nullptr|auto)\\b")
    private val fn = Regex("\\b(setup|loop|pinMode|digitalWrite|digitalRead|analogRead|analogWrite|delay|millis|micros|Serial|Wire|SPI)\\b")
    private val num = Regex("\\b(0x[0-9A-Fa-f]+|\\d+(\\.\\d+)?[UuLlFf]*)\\b")
    private val pre = Regex("(?m)^\\s*#\\w+.*$")
    private val str = Regex("\"(\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])'")
    private val comment = Regex("//.*|/\\*[\\s\\S]*?\\*/")

    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val out = buildAnnotatedString {
            append(s)
            if (s.length < 60_000) {
                kw.findAll(s).forEach { addStyle(SpanStyle(color = Color(0xFFFF7B9C)), it.range.first, it.range.last + 1) }
                fn.findAll(s).forEach { addStyle(SpanStyle(color = Color(0xFF79C0FF)), it.range.first, it.range.last + 1) }
                num.findAll(s).forEach { addStyle(SpanStyle(color = Color(0xFFF6AD55)), it.range.first, it.range.last + 1) }
                pre.findAll(s).forEach { addStyle(SpanStyle(color = Color(0xFFB392F0)), it.range.first, it.range.last + 1) }
                str.findAll(s).forEach { addStyle(SpanStyle(color = Color(0xFFA5D6A7)), it.range.first, it.range.last + 1) }
                comment.findAll(s).forEach {
                    addStyle(SpanStyle(color = Color(0xFF6A7785), fontStyle = FontStyle.Italic), it.range.first, it.range.last + 1)
                }
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

private val symbols = listOf("{", "}", "(", ")", ";", "<", ">", "=", "\"", "#", "/", "[", "]", "&", "|", "!", "+", "-", "*", ",", "_", "  ")

@Composable
fun CodeScreen(vm: AppViewModel) {
    val p = vm.project ?: run { NoProject(vm); return }
    var boardMenu by remember { mutableStateOf(false) }
    var methodMenu by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(true) }
    var showHeader by remember { mutableStateOf(false) }
    val idle = vm.busy == null

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = { vm.verify() }, enabled = idle) { Text("✓ Verify") }
            Button(onClick = { vm.upload() }, enabled = idle) { Text("→ Upload") }
            Box {
                FilledTonalButton(onClick = { methodMenu = true }) {
                    Text(if (vm.board.supportsOta && p.method == "ota") "Wi-Fi" else if (vm.board.uploader.name == "UF2") "UF2" else "USB")
                }
                DropdownMenu(methodMenu, { methodMenu = false }) {
                    DropdownMenuItem(text = { Text("USB cable") }, onClick = { vm.setMethod("usb"); methodMenu = false })
                    if (vm.board.supportsOta) DropdownMenuItem(
                        text = { Text("Wi-Fi OTA (${p.otaHost.ifBlank { "set IP in Device tab" }})") },
                        onClick = { vm.setMethod("ota"); methodMenu = false })
                }
            }
            Box {
                FilledTonalButton(onClick = { boardMenu = true }) { Text(vm.board.name, maxLines = 1) }
                DropdownMenu(boardMenu, { boardMenu = false }) {
                    io.pinforge.model.Boards.all.forEach { b ->
                        DropdownMenuItem(text = { Text(b.name) }, onClick = { vm.setBoard(b.id); boardMenu = false })
                    }
                }
            }
            TextButton(onClick = { vm.saveAll() }) { Text(if (vm.dirty) "Save •" else "Saved") }
            TextButton(onClick = { showHeader = true }) { Text("wiring.h") }
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Log") }
        }
        if (vm.board.note.isNotEmpty()) Text(
            vm.board.note, style = MaterialTheme.typography.labelSmall, color = Amber,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0D1117))) {
            BasicTextField(
                value = vm.code,
                onValueChange = { vm.editCode(it) },
                textStyle = Mono.copy(color = Color(0xFFE6EDF3), fontSize = 13.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(Teal),
                visualTransformation = CppHighlight,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii
                ),
                modifier = Modifier.fillMaxSize().padding(10.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState())
        ) {
            symbols.forEach { s ->
                TextButton(onClick = { vm.insertText(s) }) { Text(if (s == "  ") "⇥" else s, style = Mono.copy(fontSize = 15.sp)) }
            }
        }
        if (showLog) LogPanel(vm.console, Modifier.fillMaxWidth().height(170.dp))
    }

    if (showHeader) {
        val text = remember(p) { java.io.File(p.dir, "wiring.h").takeIf { it.exists() }?.readText() ?: "" }
        AlertDialog(
            onDismissRequest = { showHeader = false },
            title = { Text("wiring.h") },
            text = { LazyColumn { item { Text(text, style = Mono) } } },
            confirmButton = { TextButton(onClick = { showHeader = false }) { Text("Close") } }
        )
    }
}
