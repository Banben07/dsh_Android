package dev.harness.android.ui

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.harness.android.HarnessState
import dev.harness.android.HarnessViewModel
import dev.harness.core.*
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal data class ConversationContent(val messages: List<DisplayMessage>, val activity: List<DisplayMessage>)

private val DisplayMessage.isActivity get() = kind == "tool" || kind == "reasoning"

/** Share one history group across reasoning and tools, keeping answer text in the conversation. */
internal fun conversationContent(messages: List<DisplayMessage>): ConversationContent {
    val entries = buildList {
        messages.forEachIndexed { index, message ->
            if (message.kind == "assistant") {
                val streamingTool = messages.getOrNull(index + 1)?.let { it.kind == "tool" && it.streaming } == true
                if (message.reasoning.isNotBlank()) add(message.copy(
                    key = "reasoning-${message.key}", kind = "reasoning", text = message.reasoning, reasoning = "",
                    streaming = message.streaming && message.text.isBlank() && !streamingTool,
                ))
                // A reasoning-only message belongs entirely in the activity group, without an empty answer row.
                if (message.text.isNotBlank() || message.interrupted ||
                    (message.streaming && message.reasoning.isBlank() && !streamingTool)) add(message.copy(reasoning = ""))
            } else add(message)
        }
    }
    val activity = entries.filter { it.isActivity }
    val latest = activity.lastOrNull()?.key
    return ConversationContent(entries.filter { !it.isActivity || it.key == latest }, activity)
}

@Composable
fun Conversation(state: HarnessState, vm: HarnessViewModel, modifier: Modifier) {
    key(state.server, state.selectedId) {
        val content = remember(state.messages) { conversationContent(state.messages) }
        val messages = content.messages
        // Start at the latest visible row, avoiding layout of old Markdown before jumping.
        val list = rememberLazyListState(initialFirstVisibleItemIndex = messages.size)
        val scope = rememberCoroutineScope()
        var following by remember { mutableStateOf(true) }
        LaunchedEffect(list) {
            snapshotFlow { list.isScrollInProgress }.distinctUntilChanged().collect { moving ->
                if (moving) following = !list.canScrollForward
            }
        }
        val tail = state.messages.lastOrNull()
        LaunchedEffect(state.messages.size, tail?.text?.length, tail?.reasoning?.length, tail?.arguments?.length) {
            if (following && messages.isNotEmpty()) list.scrollToItem(messages.size)
        }
        Box(modifier.fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                item(key = "history") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.hasMore) TextButton(onClick = { following = false; vm.loadOlder() }, enabled = state.connected && !state.syncing && !state.loadingOlder) {
                            if (state.loadingOlder) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.History, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp)); Text("加载更早的消息")
                        }
                    }
                }
                items(messages, key = { if (it.isActivity) "session-activity" else it.key }) { message ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.kind == "user") Alignment.CenterEnd else Alignment.CenterStart) {
                        if (message.isActivity) SessionActivity(content.activity, Modifier.widthIn(max = 740.dp))
                        else MessageCard(message, Modifier.widthIn(max = 740.dp), loadImage = { attachmentId -> vm.readImage(state.selectedId.orEmpty(), attachmentId) })
                    }
                }
                if (state.messages.isEmpty() && !state.loading) item {
                    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp)); Text("从一个想法开始", style = MaterialTheme.typography.titleMedium)
                        Text(state.session?.cwd.orEmpty(), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.loading) item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        if (state.creatingSelected) Text("正在创建会话…", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (!following && messages.isNotEmpty()) SmallFloatingActionButton(onClick = { following = true; scope.launch { list.animateScrollToItem(messages.size) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), containerColor = MaterialTheme.colorScheme.surface) { Icon(Icons.Default.ArrowDownward, "跳到最新消息") }
        }
    }
}

@Composable
fun MessageCard(message: DisplayMessage, modifier: Modifier = Modifier, loadImage: (suspend (String) -> ByteArray)? = null) {
    when (message.kind) {
        "user" -> Surface(modifier, color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                if (message.text.isNotBlank()) SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                MessageAttachments(message.attachments, loadImage)
            }
        }
        "assistant" -> Column(modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(25); Spacer(Modifier.width(9.dp)); Text("Harness", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (message.streaming) { Spacer(Modifier.width(10.dp)); Text("正在生成回复", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall) }
            }
            if (message.text.isNotEmpty()) Markdown(message.text, Modifier.fillMaxWidth().padding(top = 12.dp))
            if (message.interrupted) Text("回复已中断", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            if (message.streaming && message.text.isEmpty() && message.reasoning.isEmpty()) LinearProgressIndicator(Modifier.padding(top = 16.dp).width(90.dp))
        }
        "tool" -> ToolCard(message, modifier.fillMaxWidth())
        "command" -> Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(14.dp)) {
                Text(message.name, style = MaterialTheme.typography.labelLarge)
                Text(message.text, style = MaterialTheme.typography.bodySmall, color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        "context" -> {
            var expanded by rememberSaveable(message.key) { mutableStateOf(false) }
            Column(modifier) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Default.Description, null, Modifier.size(15.dp)); Spacer(Modifier.width(8.dp)); Text("上下文与系统通知", style = MaterialTheme.typography.labelSmall) }
                if (expanded) Markdown(message.text, Modifier.fillMaxWidth())
            }
        }
        else -> Text(message.text, modifier, style = MaterialTheme.typography.bodySmall, color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SessionActivity(activity: List<DisplayMessage>, modifier: Modifier = Modifier) {
    val latest = activity.lastOrNull() ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var visibleCount by rememberSaveable { mutableIntStateOf(20) }
    Column(modifier.fillMaxWidth()) {
        Text("思考与工具", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        ActivityCard(latest, Modifier.fillMaxWidth())
        if (activity.size > 1) {
            TextButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("${if (expanded) "收起" else "展开"}历史过程（${activity.size - 1}）", style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                activity.dropLast(1).asReversed().take(visibleCount).forEach { item ->
                    key(item.key) { Box(Modifier.padding(top = 6.dp)) { ActivityCard(item, Modifier.fillMaxWidth()) } }
                }
                if (activity.size - 1 > visibleCount) TextButton(onClick = { visibleCount += 20 }) { Text("查看更多历史过程") }
            }
        }
    }
}

@Composable
private fun ActivityCard(message: DisplayMessage, modifier: Modifier) {
    if (message.kind == "tool") ToolCard(message, modifier)
    else {
        var expanded by rememberSaveable(message.key) { mutableStateOf(false) }
        Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column {
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Psychology, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("思考过程", style = MaterialTheme.typography.labelLarge)
                        Text(if (message.interrupted) "已中断" else if (message.streaming) "正在思考" else "已完成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起思考过程" else "展开思考过程", Modifier.size(20.dp))
                }
                if (expanded) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SelectionContainer { Text(message.text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(message: DisplayMessage, modifier: Modifier) {
    var expanded by rememberSaveable(message.key) { mutableStateOf(false) }
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (message.isError) Icons.Default.ErrorOutline else if (message.result != null) Icons.Default.CheckCircleOutline else Icons.Default.Terminal, null, Modifier.size(19.dp), tint = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(message.name.ifBlank { "工具调用" }, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                    Text(if (message.isError) "执行失败" else if (message.result != null) "已完成" else if (message.streaming) "准备调用" else "等待结果", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起工具详情" else "展开工具详情", Modifier.size(20.dp))
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(14.dp)) {
                    if (message.arguments.isNotBlank()) {
                        Text("参数", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        val formatted = remember(message.arguments) { runCatching { pretty(wireJson.parseToJsonElement(message.arguments)) }.getOrDefault(message.arguments) }
                        CodeText(formatted)
                    }
                    message.result?.let { text ->
                        Spacer(Modifier.height(12.dp)); Text("结果", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        var full by rememberSaveable(message.key) { mutableStateOf(false) }
                        CodeText(if (full || text.length < 8000) text else text.take(8000) + "\n…")
                        if (!full && text.length >= 8000) TextButton(onClick = { full = true }) { Text("展开完整结果（${text.length} 字符）") }
                    }
                }
            }
        }
    }
}

@Composable
fun CodeText(text: String) {
    SelectionContainer {
        Text(text, Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private data class MarkdownBinding(val style: String, val renderer: Markwon, val text: String)

/** Native Android TextView with Markwon spans. No HTML document, browser engine or WebView. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val code = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val textSizePx = with(LocalDensity.current) { 16.sp.toPx() }
    val lineSpacingPx = with(LocalDensity.current) { 5.sp.toPx() }
    AndroidView(modifier = modifier, factory = { context ->
        TextView(context).apply {
            setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance()
        }
    }, update = { view ->
        view.setTextColor(foreground); view.setLinkTextColor(primary)
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
        view.setLineSpacing(lineSpacingPx, 1f)
        val styleKey = "$foreground:$primary:$code:$textSizePx"
        val cached = view.tag as? MarkdownBinding
        val markwon = if (cached?.style == styleKey) cached.renderer else Markwon.builder(view.context)
            .usePlugin(TablePlugin.create(view.context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.codeBackgroundColor(code).codeTextColor(foreground).linkColor(primary)
                }
            }).build()
        if (cached?.style != styleKey || cached.text != text) {
            markwon.setMarkdown(view, text)
            view.tag = MarkdownBinding(styleKey, markwon, text)
        }
    })
}
