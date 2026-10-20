package io.pinforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pinforge.AppViewModel

val Teal = Color(0xFF4FD1C5)
val Amber = Color(0xFFF6AD55)

private val scheme = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201C),
    secondary = Amber,
    onSecondary = Color(0xFF2A1700),
    background = Color(0xFF0F1419),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF1F2630),
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF9AA7B4),
    error = Color(0xFFFF7B72),
)

@Composable
fun PinTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

/** Scrolling log panel that follows the newest line. */
@Composable
fun LogPanel(lines: List<String>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    SelectionContainer(modifier.background(Color(0xFF0A0D11))) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            itemsIndexed(lines) { _, l ->
                val c = when {
                    l.startsWith("✗") || l.contains("error:", true) -> MaterialTheme.colorScheme.error
                    l.startsWith("✓") -> Teal
                    l.startsWith("▶") || l.startsWith("⚠") || l.contains("warning:", true) -> Amber
                    else -> Color(0xFFB8C4CF)
                }
                Text(l, style = Mono.copy(color = c, fontSize = 11.sp))
            }
        }
    }
}

@Composable
fun NoProject(vm: AppViewModel) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No project open", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { vm.tab = 0 }) { Text("Open or create a project") }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)
    )
}
