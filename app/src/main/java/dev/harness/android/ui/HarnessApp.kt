package dev.harness.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.harness.android.*
import dev.harness.core.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessApp(state: HarnessState, vm: HarnessViewModel) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var choosingModel by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var questionId by rememberSaveable { mutableStateOf<String?>(null) }
    val openNew = { creating = true; scope.launch { drawer.close() }; Unit }
    val chooseSession: (String) -> Unit = { vm.selectSession(it); scope.launch { drawer.close() } }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 840.dp
        val chat: @Composable () -> Unit = {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(state.session?.title ?: "Harness", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                Text(when (state.connection) {
                                    ConnectionStatus.CONNECTED -> if (state.session?.running == true) "正在执行" else "已连接 · ${state.server.removePrefix("http://").removePrefix("https://") }"
                                    ConnectionStatus.CONNECTING -> "正在连接服务…"
                                    ConnectionStatus.RETRYING -> "正在重新连接…"
                                    ConnectionStatus.OFFLINE -> "未连接"
                                }, color = if (state.connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        navigationIcon = { if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "打开会话列表") } },
                        actions = {
                            if (state.selectedId != null) IconButton(onClick = { renaming = true }, enabled = state.connected) { Icon(Icons.Default.Edit, "重命名会话", modifier = Modifier.size(20.dp)) }
                            IconButton(onClick = vm::settings) { Icon(Icons.Default.Tune, "连接设置") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                },
            ) { insets ->
                Column(Modifier.fillMaxSize().padding(insets).imePadding()) {
                    state.error?.let { ErrorBanner(it, vm::clearError) }
                    if (state.connection == ConnectionStatus.RETRYING || state.connection == ConnectionStatus.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.selectedId == null) {
                        EmptyHome(state, openNew, Modifier.weight(1f))
                    } else {
                        Conversation(state, vm, Modifier.weight(1f))
                    }
                    if (state.pending.isNotEmpty()) {
                        val pending = state.pending.firstOrNull { it.agentId == state.selectedId } ?: state.pending.first()
                        Surface(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                            onClick = {
                                if (pending.agentId != state.selectedId && state.sessions.any { it.id == pending.agentId && !it.isChild }) vm.selectSession(pending.agentId)
                                questionId = pending.eventId
                            }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FrontHand, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (pending.kind == "approval/request") "有操作需要你的确认" else "助手有问题需要你回答", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                    Text(if (pending.agentId == state.selectedId) "点击查看 · ${state.pending.size} 项待处理" else "来自其他会话 · 点击查看", style = MaterialTheme.typography.labelSmall)
                                }
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                    if (state.selectedId != null) Composer(state, vm, onModel = { choosingModel = true })
                    else if (!state.connected) TextButton(onClick = vm::settings, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp)) { Text("设置服务器连接") }
                }
            }
        }
        if (wide) {
            Row {
                Sidebar(state, chooseSession, openNew, vm::refresh, vm::settings, Modifier.width(300.dp).fillMaxHeight())
                VerticalDivider()
                Box(Modifier.weight(1f)) { chat() }
            }
        } else {
            ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = !state.showConnection, drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(320.dp), drawerContainerColor = MaterialTheme.colorScheme.surface) {
                    Sidebar(state, chooseSession, openNew, vm::refresh, { scope.launch { drawer.close() }; vm.settings() }, Modifier.fillMaxSize())
                }
            }, content = chat)
        }
    }
    if (state.showConnection) ConnectionDialog(state, vm::connect, vm::closeSettings, vm::logout)
    if (creating) NewSessionDialog(state, onDismiss = { creating = false }, onCreate = { workspace, path, preset -> vm.createSession(workspace, path, preset) { creating = false } })
    if (choosingModel) ModelDialog(state, onDismiss = { choosingModel = false }) { model, effort -> vm.selectModel(model, effort); choosingModel = false }
    if (renaming) RenameDialog(state.session?.title.orEmpty(), { renaming = false }) { vm.rename(it); renaming = false }
    state.pending.firstOrNull { it.eventId == questionId }?.let { pending ->
        QuestionDialog(pending, state, { questionId = null }) { value -> vm.answer(pending, value) }
    }
}

@Composable
private fun Sidebar(state: HarnessState, select: (String) -> Unit, onNew: () -> Unit, onRefresh: () -> Unit, settings: () -> Unit, modifier: Modifier) {
    var search by rememberSaveable { mutableStateOf("") }
    var workspace by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val workspaceSessions = state.workspaces.firstOrNull { it.id == workspace }?.sessions?.toSet()
    val sessions = state.sessions.filter {
        !it.isChild && (it.id in state.archived) == showArchived &&
            (workspaceSessions == null || it.id in workspaceSessions) &&
            (search.isBlank() || it.title.contains(search, true) || it.cwd.contains(search, true))
    }
    Column(modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 20.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(38)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text("Harness", fontWeight = FontWeight.Bold, fontSize = 23.sp); Text("你的远程工作台", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onRefresh, enabled = state.connected) { Icon(Icons.Default.Refresh, "刷新会话") }
        }
        FilledTonalButton(onClick = onNew, enabled = state.connected, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("新建会话")
        }
        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), placeholder = { Text("搜索会话") }, leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) }, singleLine = true, shape = RoundedCornerShape(14.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(workspace == null && !showArchived, { workspace = null; showArchived = false }, label = { Text("全部") })
            state.workspaces.forEach { w -> FilterChip(workspace == w.id && !showArchived, { workspace = w.id; showArchived = false }, label = { Text(w.title.ifBlank { w.path.substringAfterLast('/') }, maxLines = 1) }) }
            FilterChip(showArchived, { showArchived = !showArchived; workspace = null }, label = { Text("已归档") })
        }
        Text("会话  ${sessions.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sessions, key = { it.id }) { session ->
                val selected = session.id == state.selectedId
                Surface(onClick = { select(session.id) }, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (session.running) Icons.Default.PlayCircle else Icons.Default.ChatBubbleOutline, null, modifier = Modifier.size(19.dp), tint = if (selected || session.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            if (session.cwd.isNotBlank()) Text(session.cwd.substringAfterLast('/').ifBlank { session.cwd }, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (sessions.isEmpty()) item { Text(if (search.isNotBlank()) "没有匹配的会话" else "会话会出现在这里", modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider()
        TextButton(onClick = settings, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 10.dp)) { Icon(Icons.Default.Dns, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("连接与服务器") }
    }
}

@Composable
private fun EmptyHome(state: HarnessState, onNew: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BrandMark(64)
        Spacer(Modifier.height(26.dp))
        Text("你的工作，随身继续。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text("连接你的 DeepSeek harness，\n在手机上对话、查看进度、确认操作。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(30.dp))
        Button(onClick = onNew, enabled = state.connected, contentPadding = PaddingValues(horizontal = 25.dp, vertical = 15.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("开始新会话") }
        Spacer(Modifier.height(36.dp))
        FeatureHint(Icons.Default.Lan, "连接自己的服务", "使用服务器地址与已有工作空间")
        FeatureHint(Icons.Default.Forum, "会话同步", "从上次的进度继续，实时查看回复")
        FeatureHint(Icons.Default.CheckCircleOutline, "由你确认", "在原生界面回答问题和批准操作")
    }
}

@Composable
private fun FeatureHint(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column { Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun BrandMark(size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        Text("H", fontSize = (size * 0.52).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun ErrorBanner(message: String, dismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "关闭提示", Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun Composer(state: HarnessState, vm: HarnessViewModel, onModel: () -> Unit) {
    var draft by remember(state.selectedId) { mutableStateOf(vm.draft()) }
    var steer by rememberSaveable(state.selectedId) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.widthIn(max = 900.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (state.queued) Text("消息已送达，等待处理", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(8.dp)) {
                    TextField(draft, { draft = it; vm.setDraft(it) }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("描述你想做的事…", style = MaterialTheme.typography.bodyMedium) }, minLines = 1, maxLines = 6,
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onModel, enabled = state.connected && state.models.isNotEmpty(), modifier = Modifier.weight(1f, fill = false)) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp));
                            Text(state.selectedModel.text("model").ifBlank { "模型" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.session?.running == true) {
                            IconToggleButton(steer, { steer = it }, enabled = state.connected) { Icon(Icons.AutoMirrored.Filled.AltRoute, if (steer) "当前为引导模式，点击切换排队" else "当前为排队模式，点击切换引导", Modifier.size(19.dp)) }
                            IconButton(onClick = vm::cancelTurn, enabled = state.connected) { Icon(Icons.Default.StopCircle, "停止当前执行", tint = MaterialTheme.colorScheme.error) }
                        }
                        FilledIconButton(onClick = {
                            val submitted = draft
                            vm.send(submitted, steer) { if (draft == submitted) { draft = ""; vm.setDraft("") } }
                        }, enabled = state.connected && draft.isNotBlank() && !state.sending && !state.loading, shape = RoundedCornerShape(16.dp)) {
                            if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Filled.Send, "发送消息", Modifier.size(20.dp))
                        }
                    }
                }
            }
            Text(if (state.session?.running == true) { if (steer) "引导模式 · 消息会用于调整当前任务" else "排队模式 · 消息会加入待处理队列" } else "模型与工具在你的服务器上运行", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 7.dp).align(Alignment.CenterHorizontally))
        }
    }
}
