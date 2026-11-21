package me.rerere.rikkahub.ui.components.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.ai.root.RootGate
import me.rerere.rikkahub.data.ai.root.RootRisk
import org.koin.compose.koinInject

/** 全局 root 确认遮罩：任何界面触发 root 工具时都会显示。 */
@Composable
fun RootConfirmOverlay() {
    val gate = koinInject<RootGate>()
    val request by gate.current.collectAsStateWithLifecycle()
    val current = request ?: return

    val countdownSeconds = if (current.risk == RootRisk.S0) 0 else 5
    val requiresPhrase = current.risk == RootRisk.S3 || current.risk == RootRisk.S4

    var remaining by remember(current.id) { mutableIntStateOf(countdownSeconds) }
    var phrase by remember(current.id) { mutableStateOf("") }

    LaunchedEffect(current.id) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }

    val canAllow = remaining <= 0 && (!requiresPhrase || phrase.trim().equals(current.phrase, ignoreCase = true))

    AlertDialog(
        onDismissRequest = { gate.resolve(current.id, RootGate.Decision(false, reason = "用户关闭了确认框")) },
        title = { Text("root 权限请求") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "工具：${current.toolName} · 风险 ${current.risk.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                SelectionContainer {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = current.command,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (current.reasons.isNotEmpty()) {
                    Text(
                        text = current.reasons.joinToString("；"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (current.reviewNote != null) {
                    Text(
                        text = "自动审查：${current.reviewNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (requiresPhrase) {
                    Text(
                        text = "如确认执行，请输入：${current.phrase}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = phrase,
                        onValueChange = { phrase = it },
                        singleLine = true,
                        label = { Text("输入上方短语") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (remaining > 0) {
                    Text(
                        text = "请阅读以上内容，${remaining}s 后可继续",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canAllow,
                onClick = { gate.resolve(current.id, RootGate.Decision(true)) },
            ) { Text("允许执行") }
        },
        dismissButton = {
            TextButton(onClick = { gate.resolve(current.id, RootGate.Decision(false, reason = "用户拒绝")) }) {
                Text("拒绝")
            }
        },
    )
}
