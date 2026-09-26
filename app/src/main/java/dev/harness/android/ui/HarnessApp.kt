package dev.harness.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.harness.android.*
import dev.harness.core.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessApp(state: HarnessState, vm: HarnessViewModel) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var editingDefaults by rememberSaveable { mutableStateOf(false) }
    var choosingModel by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var questionId by rememberSaveable { mutableStateOf<String?>(null) }
    val openNew = { vm.quickCreateSession(); scope.launch { drawer.close() }; Unit }
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
                                Text(if (state.creatingSelected) "新对话" else state.session?.title ?: "DeepSeek Harness", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                Text(when (state.connection) {
                                    ConnectionStatus.CONNECTED -> if (state.creatingSelected) "正在创建会话…" else if (state.syncing) "正在同步会话…" else if (state.session?.running == true) "正在执行" else "已连接"
                                    ConnectionStatus.CONNECTING -> "正在连接服务…"
                                    ConnectionStatus.RETRYING -> "正在重新连接…"
                                    ConnectionStatus.OFFLINE -> "未连接"
                                }, color = if (state.connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        navigationIcon = { if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "打开会话列表") } },
                        actions = {
                            if (state.selectedId != null) IconButton(onClick = { renaming = true }, enabled = state.connected && !state.creatingSelected) { Icon(Icons.Default.Edit, "重命名会话", modifier = Modifier.size(20.dp)) }
                            // Keep the new conversation action at the far right for quick access.
                            IconButton(onClick = openNew, enabled = state.connected && !state.creating) {
                                Icon(Icons.Default.Add, "新建对话", modifier = Modifier.size(24.dp))
                            }
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
                    if (state.selectedId != null && !state.creatingSelected) Composer(state, vm, onModel = { choosingModel = true })
                    else if (!state.connected) TextButton(onClick = vm::settings, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp)) { Text("设置服务器连接") }
                }
            }
        }
        if (wide) {
            Row {
                Sidebar(state, chooseSession, openNew, vm::refresh, vm::settings, vm::setArchived, Modifier.width(300.dp).fillMaxHeight())
                VerticalDivider()
                Box(Modifier.weight(1f)) { chat() }
            }
        } else {
            ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = !state.showConnection, drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(320.dp), drawerContainerColor = MaterialTheme.colorScheme.surface) {
                    Sidebar(state, chooseSession, openNew, vm::refresh, { scope.launch { drawer.close() }; vm.settings() }, vm::setArchived, Modifier.fillMaxSize())
                }
            }, content = chat)
        }
    }
    if (state.showConnection) ConnectionDialog(state, vm::connect, vm::closeSettings, vm::logout,
        defaults = { editingDefaults = true }, notifications = vm::setNotifications, testConnection = vm::testConnection,
        fontScale = vm::setFontScale, backgroundConnection = vm::setBackgroundConnection)
    if (editingDefaults) NewSessionDefaultsDialog(state, onDismiss = { editingDefaults = false }) { vm.saveDefaults(it); editingDefaults = false }
    if (choosingModel) ModelDialog(state, onDismiss = { choosingModel = false }) { model, effort -> vm.selectModel(model, effort); choosingModel = false }
    if (renaming) RenameDialog(state.session?.title.orEmpty(), { renaming = false }) { vm.rename(it); renaming = false }
    state.commandResult?.let { result -> AlertDialog(onDismissRequest = vm::clearCommandResult, title = { Text("命令结果") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Markdown(result) } }, confirmButton = { TextButton(onClick = vm::clearCommandResult) { Text("完成") } }) }
    state.pending.firstOrNull { it.eventId == questionId }?.let { pending ->
        QuestionDialog(pending, state, { questionId = null }) { value -> vm.answer(pending, value) }
    }
}

@Composable
private fun Sidebar(state: HarnessState, select: (String) -> Unit, onNew: () -> Unit, onRefresh: () -> Unit, settings: () -> Unit, archive: (String, Boolean) -> Unit, modifier: Modifier) {
    var search by rememberSaveable { mutableStateOf("") }
    var workspace by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val workspaceSessions = state.workspaces.firstOrNull { it.id == workspace }?.sessions?.toSet()
    val sessions = state.sessions.filter {
        // Sessions never used for a conversation disappear once another session is open.
        !it.isChild && (!it.blank || it.id == state.selectedId) && (it.id in state.archived) == showArchived &&
            (workspaceSessions == null || it.id in workspaceSessions) &&
            (search.isBlank() || it.title.contains(search, true) || it.cwd.contains(search, true))
    }
    Column(modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 20.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(38)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text("DeepSeek", fontWeight = FontWeight.Bold, fontSize = 23.sp); Text("Harness · 你的远程工作台", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onRefresh, enabled = state.connected) { Icon(Icons.Default.Refresh, "刷新会话") }
        }
        FilledTonalButton(onClick = onNew, enabled = state.connected && !state.creating, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
            if (state.creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp)); Text("新建对话")
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
                SessionListItem(session, session.id == state.selectedId, session.id in state.archived,
                    canArchive = state.connected && state.archivingSessionId == null, busy = state.archivingSessionId == session.id,
                    onOpen = { select(session.id) }, onArchive = { archive(session.id, it) })
            }
            if (sessions.isEmpty()) item { Text(if (search.isNotBlank()) "没有匹配的会话" else "会话会出现在这里", modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider()
        TextButton(onClick = settings, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 10.dp)) { Icon(Icons.Default.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("设置") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionListItem(session: SessionSummary, selected: Boolean, archived: Boolean, canArchive: Boolean, busy: Boolean,
    onOpen: () -> Unit, onArchive: (Boolean) -> Unit) {
    var menu by remember(session.id) { mutableStateOf(false) }
    val action = if (archived) "取消归档" else "归档会话"
    Box {
        Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(
                onClickLabel = "打开会话", onClick = onOpen, onLongClickLabel = "会话操作", onLongClick = { menu = true },
            ).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                else Icon(if (session.running) Icons.Default.PlayCircle else Icons.Default.ChatBubbleOutline, null, modifier = Modifier.size(19.dp), tint = if (selected || session.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    if (session.cwd.isNotBlank()) Text(session.cwd.substringAfterLast('/').ifBlank { session.cwd }, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(action) }, enabled = canArchive,
                leadingIcon = { Icon(if (archived) Icons.Default.Unarchive else Icons.Default.Archive, null) },
                onClick = { menu = false; onArchive(!archived) })
        }
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
        Button(onClick = onNew, enabled = state.connected && !state.creating, contentPadding = PaddingValues(horizontal = 25.dp, vertical = 15.dp), shape = RoundedCornerShape(16.dp)) {
            if (state.creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp)); Text("新建对话")
        }
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
    Image(painterResource(dev.harness.android.R.drawable.ic_deepseek), "DeepSeek", Modifier.size(size.dp))
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
    var attachmentMenu by remember { mutableStateOf(false) }
    var pickerSession by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerServer by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addAttachments(pickerServer, pickerSession, uris) }
    var exportSession by rememberSaveable { mutableStateOf<String?>(null) }
    var exportServer by rememberSaveable { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportSession(exportServer, exportSession, uri)
    }
    val sendingHere = state.sendingSessionId == state.selectedId
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun setDraft(value: String) { draft = value; vm.setDraft(value) }
    fun localCommand(name: String): Boolean {
        when (name) {
            "model" -> onModel()
            "file" -> { pickerSession = state.selectedId; pickerServer = state.server; picker.launch(arrayOf("*/*")) }
            "new" -> vm.quickCreateSession()
            "settings" -> vm.settings()
            "export" -> {
                if (state.commands.none { it.name == "export" }) return false
                if (state.exporting) return true
                exportSession = state.selectedId; exportServer = state.server
                exporter.launch("dsh-session-${state.selectedId}.zip")
            }
            else -> return false
        }
        setDraft(""); return true
    }
    val nativeCommands = listOf(SlashCommand("model", "选择模型与思考强度", "", false), SlashCommand("file", "选择图片或文件", "", false), SlashCommand("new", "新建对话", "", false), SlashCommand("settings", "打开应用设置", "", false))
    val commandRows = state.commands + nativeCommands.filter { local -> state.commands.none { it.name == local.name } }
    val commandQuery = draft.trimStart().takeIf { it.startsWith("/") && it.none(Char::isWhitespace) }?.drop(1)
    if (state.exporting) AlertDialog(onDismissRequest = {}, title = { Text("正在导出会话") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("已保存 ${sizeLabel(state.exportedBytes)}")
        }
    }, confirmButton = { TextButton(onClick = vm::cancelExport) { Text("取消导出") } })
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.widthIn(max = 900.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (state.queued) Text("消息已送达，等待处理", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(8.dp)) {
                    DraftAttachments(state.attachments, sendingHere, vm::removeAttachment)
                    if (commandQuery != null) {
                        SlashMenu(commandRows, commandQuery, state.commandsLoading, state.commandsError, vm::loadCommands) { command ->
                            if (!localCommand(command.name)) setDraft("/${command.name} ")
                        }
                    }
                    TextField(draft, { draft = it; vm.setDraft(it) }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("描述你想做的事…", style = MaterialTheme.typography.bodyMedium) }, minLines = 1, maxLines = 6,
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { attachmentMenu = true }, enabled = !sendingHere) { Icon(Icons.Default.AttachFile, "添加图片或文件", Modifier.size(21.dp)) }
                            DropdownMenu(attachmentMenu, { attachmentMenu = false }) {
                                for ((label, mime) in listOf("选择图片" to "image/*", "选择文件" to "*/*")) DropdownMenuItem(text = { Text(label) }, onClick = {
                                    attachmentMenu = false; pickerSession = state.selectedId; pickerServer = state.server; picker.launch(arrayOf(mime))
                                }, leadingIcon = { Icon(if (mime == "image/*") Icons.Default.Image else Icons.Default.Description, null) })
                            }
                        }
                        TextButton(onClick = onModel, enabled = state.connected && state.models.isNotEmpty(), modifier = Modifier.weight(1f, fill = false)) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp));
                            Text(state.selectedModel.text("model").ifBlank { "模型" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        if (sendingHere) IconButton(onClick = vm::cancelSend) { Icon(Icons.Default.Cancel, "取消上传或发送", Modifier.size(20.dp)) }
                        if (state.session?.running == true) {
                            IconToggleButton(steer, { steer = it }, enabled = state.connected) { Icon(Icons.AutoMirrored.Filled.AltRoute, if (steer) "当前为引导模式，点击切换排队" else "当前为排队模式，点击切换引导", Modifier.size(19.dp)) }
                            IconButton(onClick = vm::cancelTurn, enabled = state.connected) { Icon(Icons.Default.StopCircle, "停止当前执行", tint = MaterialTheme.colorScheme.error) }
                        }
                        FilledIconButton(onClick = {
                            val submitted = draft
                            keyboard?.hide()
                            focusManager.clearFocus(force = true)
                            if (!submitted.trim().startsWith("/") || !localCommand(submitted.trim().drop(1))) {
                                vm.send(submitted, steer) { if (draft == submitted) setDraft("") }
                            }
                        }, enabled = state.connected && (draft.isNotBlank() || state.attachments.isNotEmpty()) && !state.sending && !state.loading, shape = RoundedCornerShape(16.dp)) {
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
