package dev.harness.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.harness.android.*
import dev.harness.core.*
import kotlinx.serialization.json.*

@Composable
fun ConnectionDialog(state: HarnessState, connect: (String, String) -> Unit, dismiss: () -> Unit, logout: () -> Unit) {
    var server by remember { mutableStateOf(state.server) }
    var token by remember { mutableStateOf("") } // Intentionally not saveable: never persist a launch token.
    var visible by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(dismissOnClickOutside = state.connected)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BrandMark(46)
                Text("连接你的 Harness", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("填写手机可以访问的 harness 地址，支持 HTTP、HTTPS，以及带 token 的启动链接。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(server, { server = it }, label = { Text("服务地址") }, placeholder = { Text("192.168.1.10:3000 或 https://harness.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), leadingIcon = { Icon(Icons.Default.Dns, null, Modifier.size(20.dp)) })
                OutlinedTextField(token, { token = it }, label = { Text("启动 Token") }, supportingText = { Text("首次登录需要；已有 Cookie 时可留空。这里不是 DeepSeek API Key。") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "隐藏 Token" else "显示 Token") } })
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { connect(server, token); token = "" }, modifier = Modifier.fillMaxWidth().height(50.dp), enabled = server.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Link, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("连接服务") }
                Text("连接 Cookie 使用 Android Keystore 加密保存在本机。模型与工具继续在你的服务器上运行。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.server.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = logout) { Text("清除登录状态") }
                    TextButton(onClick = dismiss) { Text("返回") }
                }
            }
        }
    }
}

@Composable
fun NewSessionDialog(state: HarnessState, onDismiss: () -> Unit, onCreate: (String?, String, String?) -> Unit) {
    var workspace by rememberSaveable { mutableStateOf<String?>(null) }
    var cwd by rememberSaveable { mutableStateOf("") }
    var preset by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!state.creating) onDismiss() }, title = { Text("新建会话") }, icon = { Icon(Icons.Default.AddComment, null) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("工作空间", style = MaterialTheme.typography.labelLarge)
            ChoiceRow(workspace == null, "使用服务器默认目录", "也可以在下方填写路径") { workspace = null }
            state.workspaces.forEach { w -> ChoiceRow(workspace == w.id, w.title.ifBlank { w.path }, w.path) { workspace = w.id } }
            if (workspace == null) OutlinedTextField(cwd, { cwd = it }, label = { Text("服务器上的工作目录（可选）") }, placeholder = { Text("/home/user/project") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true)
            if (state.presets.isNotEmpty()) {
                HorizontalDivider()
                Text("代理预设", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(preset == null, "默认预设", "使用 harness 的默认配置") { preset = null }
                state.presets.forEach { p -> ChoiceRow(preset == p.id, p.name, p.description) { preset = p.id } }
            }
        }
    }, confirmButton = { Button(onClick = { onCreate(workspace, cwd, preset) }, enabled = state.connected && !state.creating) { if (state.creating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("创建会话") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !state.creating) { Text("取消") } })
}

@Composable
fun ModelDialog(state: HarnessState, onDismiss: () -> Unit, onSelect: (ModelOption, String?) -> Unit) {
    var selected by remember { mutableStateOf(state.models.firstOrNull { it.id == state.selectedModel.text("model") && it.provider == state.selectedModel.text("provider") }) }
    var effort by remember { mutableStateOf(state.selectedModel.text("reasoningEffort")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择模型") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.models.forEach { m -> ChoiceRow(m == selected, m.name.ifBlank { m.id }, "${m.provider} / ${m.id}") { selected = m; effort = m.defaultEffort } }
            selected?.takeIf { it.efforts.isNotEmpty() }?.let { m ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("思考强度", style = MaterialTheme.typography.labelLarge)
                m.efforts.forEach { (id, name) -> ChoiceRow(effort == id, name, "") { effort = id } }
            }
        }
    }, confirmButton = { TextButton(onClick = { selected?.let { onSelect(it, effort.ifBlank { null }) } }, enabled = selected != null && state.connected) { Text("应用") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("重命名会话") }, text = { OutlinedTextField(title, { title = it }, label = { Text("会话名称") }, singleLine = true) }, confirmButton = { TextButton(onClick = { onRename(title) }, enabled = title.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun QuestionDialog(pending: PendingQuestion, state: HarnessState, onDismiss: () -> Unit, onAnswer: (JsonElement) -> Unit) {
    val approval = pending.kind == "approval/request"
    val busy = pending.eventId in state.answering
    val enabled = state.connected && !busy
    val questions = pending.request["questions"].array().map { it.obj() }
    val selections = remember(pending.eventId) { mutableStateMapOf<String, Set<String>>() }
    val custom = remember(pending.eventId) { mutableStateMapOf<String, String>() }
    val complete = questions.isNotEmpty() && questions.all { !selections[it.text("id")].isNullOrEmpty() || !custom[it.text("id")].isNullOrBlank() }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(if (approval) Icons.Default.FrontHand else Icons.Default.QuestionAnswer, null) },
        title = { Text(if (approval) "确认这次操作" else "需要你的回答") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val sessionName = state.sessions.firstOrNull { it.id == pending.agentId }?.title ?: pending.agentId.take(12)
                Text("会话：$sessionName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (approval) {
                    Text(pending.request.text("toolName"), fontWeight = FontWeight.SemiBold)
                    Markdown(pending.request.text("reason").ifBlank { "此工具请求执行需要你授权的操作。" })
                    val callId = pending.request.text("callId")
                    state.messages.firstOrNull { callId.isNotEmpty() && it.callId == callId }?.let { tool ->
                        Text("本次调用参数", style = MaterialTheme.typography.labelSmall)
                        CodeText(remember(tool.arguments) { runCatching { pretty(wireJson.parseToJsonElement(tool.arguments)) }.getOrDefault(tool.arguments) })
                    }
                    Text("允许只对这一次请求生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else questions.forEach { q ->
                    val id = q.text("id"); val multiple = q.flag("multiSelect")
                    Text(q.text("question"), style = MaterialTheme.typography.titleSmall)
                    if (q.text("detail").isNotBlank()) Markdown(q.text("detail"))
                    if (q["intent"].obj().text("kind") == "plan-review") Text("计划确认", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    q["options"].array().forEach { option ->
                        val o = option.obj(); val label = o.text("label"); val checked = label in selections[id].orEmpty()
                        val toggle = {
                            if (multiple) selections[id] = if (checked) selections[id].orEmpty() - label else selections[id].orEmpty() + label
                            else { selections[id] = setOf(label); custom[id] = "" }
                        }
                        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                            if (multiple) Checkbox(checked, { toggle() }, enabled = enabled) else RadioButton(checked, toggle, enabled = enabled)
                            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyMedium); if (o.text("description").isNotBlank()) Text(o.text("description"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    OutlinedTextField(custom[id].orEmpty(), { custom[id] = it; if (!multiple && it.isNotBlank()) selections[id] = emptySet() }, modifier = Modifier.fillMaxWidth(), label = { Text(if (q["options"].array().isEmpty()) "你的回答" else "自定义回答") }, enabled = enabled, minLines = 1, maxLines = 5, shape = RoundedCornerShape(12.dp))
                    HorizontalDivider()
                }
                if (!state.connected) Text("连接中断后需要重新同步，请稍候。", color = MaterialTheme.colorScheme.error)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (approval) onAnswer(str("allowed-once"))
                else onAnswer(jsonObject("answers" to JsonArray(questions.map { q ->
                    val id = q.text("id")
                    jsonObject("id" to str(id), "selected" to JsonArray(selections[id].orEmpty().map(::str)), "custom" to custom[id]?.trim()?.takeIf { it.isNotBlank() }?.let(::str))
                })))
            }, enabled = enabled && (approval || complete)) { if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (approval) "仅允许这一次" else "提交回答") }
        },
        dismissButton = {
            if (approval) TextButton(onClick = { onAnswer(str("rejected")) }, enabled = enabled) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
            else TextButton(onClick = onDismiss) { Text("稍后回答") }
        },
    )
}
