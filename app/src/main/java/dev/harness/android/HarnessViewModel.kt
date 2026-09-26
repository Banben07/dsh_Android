package dev.harness.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.harness.core.*
import java.util.TimeZone
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class ConnectionStatus { OFFLINE, CONNECTING, CONNECTED, RETRYING }
data class HarnessState(
    val server: String = "", val connection: ConnectionStatus = ConnectionStatus.OFFLINE,
    val showConnection: Boolean = true, val error: String? = null,
    val sessions: List<SessionSummary> = emptyList(), val workspaces: List<Workspace> = emptyList(),
    val archived: Set<String> = emptySet(), val models: List<ModelOption> = emptyList(), val presets: List<Preset> = emptyList(),
    val selectedId: String? = null, val messages: List<DisplayMessage> = emptyList(),
    val pending: List<PendingQuestion> = emptyList(), val answering: Set<String> = emptySet(),
    val selectedModel: JsonObject = emptyObject, val loading: Boolean = false,
    val hasMore: Boolean = false, val loadingOlder: Boolean = false,
    val sending: Boolean = false, val creating: Boolean = false, val queued: Boolean = false,
    val attachmentDrafts: Map<String, List<DraftAttachment>> = emptyMap(),
    val sendingSessionId: String? = null,
    val defaults: SessionDefaults = SessionDefaults(), val notifications: Boolean = false,
    val diagnostics: String? = null, val diagnosing: Boolean = false,
    val commands: List<SlashCommand> = emptyList(), val commandsLoading: Boolean = false, val commandsError: String? = null,
    val commandResult: String? = null,
    val exporting: Boolean = false, val exportedBytes: Long = 0,
) {
    val connected get() = connection == ConnectionStatus.CONNECTED
    val session get() = sessions.firstOrNull { it.id == selectedId }
    val attachments get() = attachmentDrafts[selectedId].orEmpty()
}

class HarnessViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    private val mutable = MutableStateFlow(HarnessState(server = store.server, showConnection = store.server.isBlank(), defaults = store.defaults(store.server), notifications = store.notifications))
    val state: StateFlow<HarnessState> = mutable.asStateFlow()
    private var client: HarnessClient? = null
    private var mux: HarnessMux? = null
    private var connectionJob: Job? = null
    private var pauseJob: Job? = null
    private var clientId: String? = null
    private var followId: String? = null
    private var epoch = 0L
    private var journal = SessionJournal()
    private var defaultModel = emptyObject
    private val drafts = mutableMapOf<String, String>()
    private var restoreRecent = true
    private var sendJob: Job? = null
    private var activeSendId: String? = null
    private var exportJob: Job? = null

    fun draft(): String = drafts[state.value.selectedId.orEmpty()].orEmpty()
    fun setDraft(text: String) { drafts[state.value.selectedId.orEmpty()] = text }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun settings() { mutable.update { it.copy(showConnection = true, error = null) } }
    fun testConnection(input: String) {
        if (state.value.diagnosing) return
        val address = try { ServerAddress.parse(input).copy(launchToken = null) } catch (e: Exception) { fail(e); return }
        mutable.update { it.copy(diagnosing = true, diagnostics = null) }
        viewModelScope.launch {
            try {
                val manager = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
                val vpn = manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                val network = "系统 VPN：${if (vpn) "已启用" else "未检测到"}"
                val detail = try {
                    HarnessClient(address, okhttp3.CookieJar.NO_COOKIES).use { probe ->
                        when (val status = probe.probe()) {
                            401 -> "服务器可达（HTTP 401），需要登录。"
                            403 -> "服务器可达（HTTP 403），但拒绝了这个访问地址。"
                            else -> "服务器已响应（HTTP $status），网络入口可达。"
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    connectionFailureMessage(e, "服务不可达")
                }
                mutable.update { it.copy(diagnostics = "${address.origin}\n$network\n$detail") }
            } finally { mutable.update { it.copy(diagnosing = false) } }
        }
    }
    fun closeSettings() { if (state.value.server.isNotBlank()) mutable.update { it.copy(showConnection = false) } }
    fun connect(input: String, token: String = "") {
        val address = try { ServerAddress.parse(input, token) } catch (e: Exception) { fail(e); return }
        val changed = state.value.server != address.origin
        stop()
        if (changed) { drafts.clear(); journal = SessionJournal(); restoreRecent = true }
        store.server = address.origin
        mutable.update { if (changed) HarnessState(server = address.origin, showConnection = false, defaults = store.defaults(address.origin), notifications = store.notifications) else it.copy(showConnection = false, error = null, sending = false, sendingSessionId = null) }
        client = HarnessClient(address.copy(launchToken = null), store)
        start(address.launchToken)
    }
    fun resume() {
        pauseJob?.cancel()
        if (connectionJob?.isActive == true || state.value.showConnection || state.value.server.isBlank()) return
        if (client == null) client = HarnessClient(ServerAddress.parse(state.value.server), store)
        start(null)
    }
    fun pause() {
        pauseJob = viewModelScope.launch {
            delay(8_000)
            connectionJob?.cancel(); mux?.close(); mux = null
            mutable.update { it.copy(connection = ConnectionStatus.OFFLINE, pending = emptyList()) }
        }
    }
    fun reconnect() { pauseJob?.cancel(); connectionJob?.cancel(); mux?.close(); start(null) }
    fun logout() {
        stop(); store.forgetCookies(); client = null
        drafts.clear(); mutable.update { HarnessState(server = it.server, showConnection = true, defaults = store.defaults(it.server), notifications = store.notifications) }
    }
    private fun stop(stopMonitor: Boolean = true) {
        sendJob?.cancel(); activeSendId = null
        exportJob?.cancel()
        mutable.update { it.copy(exporting = false) }
        if (stopMonitor) NotificationMonitor.stop(getApplication())
        epoch++; pauseJob?.cancel(); connectionJob?.cancel(); mux?.close(); mux = null
        client?.close(); clientId = null; followId = null
    }
    private fun start(token: String?) {
        val api = client ?: return
        val generation = ++epoch
        connectionJob = viewModelScope.launch {
            mutable.update { it.copy(connection = ConnectionStatus.CONNECTING, error = null) }
            if (token != null) {
                try { api.login(token) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    mutable.update { it.copy(connection = ConnectionStatus.OFFLINE, showConnection = true, error = connectionFailureMessage(e, "登录失败")) }
                    return@launch
                }
            }
            var retry = 0
            while (isActive && generation == epoch) {
                var active: HarnessMux? = null
                var stage = "建立实时连接失败"
                try {
                    mutable.update { it.copy(connection = if (retry == 0) ConnectionStatus.CONNECTING else ConnectionStatus.RETRYING) }
                    active = api.mux(); mux = active
                    val first = withTimeout(20_000) { active.frames.receive() }
                    val ready = first["value"].obj()
                    if (first.text("type") != "item" || first.text("streamId") != "events" || ready.text("type") != "ready" || ready.text("clientId").isBlank()) {
                        throw HarnessException("protocol/ready", "harness 实时握手失败，请确认后端接口版本")
                    }
                    clientId = ready.text("clientId")
                    // Read baseline while event frames remain queued, then apply those frames in order.
                    stage = "读取会话列表失败"
                    val sessions = api.listSessions()["items"].array().map { SessionSummary.parse(it.obj()) }
                    val recent = if (restoreRecent) store.recent(api.address.origin) else state.value.selectedId
                    restoreRecent = false
                    mutable.update { it.copy(connection = ConnectionStatus.CONNECTED, sessions = sessions,
                        selectedId = recent?.takeIf { id -> sessions.any { row -> row.id == id } }, pending = emptyList(), error = null) }
                    if (store.notifications && NotificationMonitor.appVisible) startNotifications()
                    active.open("control", "session/control")
                    active.open("workspaces", "workspace/follow")
                    state.value.selectedId?.let { follow(it) }
                    loadCatalog(api, generation)
                    retry = 0
                    stage = "同步会话失败"
                    for (frame in active.frames) process(frame)
                    throw HarnessException("stream/closed", "服务连接已断开")
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    if (generation != epoch) return@launch
                    if (e is HarnessException && (e.code.startsWith("auth/") || e.code.startsWith("protocol/"))) {
                        mutable.update { it.copy(connection = ConnectionStatus.OFFLINE, showConnection = true, pending = emptyList(), error = connectionFailureMessage(e, stage)) }
                        return@launch
                    }
                    mutable.update { it.copy(connection = ConnectionStatus.RETRYING, error = connectionFailureMessage(e, stage) + "\n正在自动重连。", pending = emptyList()) }
                    retry++
                } finally {
                    active?.close()
                    if (mux === active) { mux = null; clientId = null; followId = null }
                }
                val cap = minOf(10_000L, 500L shl minOf(retry, 5))
                delay(Random.nextLong(cap / 2, cap + 1))
            }
        }
    }
    private fun loadCatalog(api: HarnessClient, generation: Long) {
        viewModelScope.launch {
            try {
                val c = api.rpc("session/modelCatalog").obj()
                if (generation != epoch) return@launch
                defaultModel = c["default"].obj()
                val models = c["groups"].array().flatMap { group ->
                    val g = group.obj()
                    g["models"].array().map { raw ->
                        val m = raw.obj(); val reasoning = m["reasoning"].obj()
                        ModelOption(g.text("id"), m.text("id"), m.text("name"), reasoning["efforts"].array().map { it.obj().text("id") to it.obj().text("name") }, reasoning.text("defaultEffort"))
                    }
                }
                mutable.update { it.copy(models = models, selectedModel = it.selectedModel.ifEmpty { defaultModel }) }
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        viewModelScope.launch {
            try {
                val c = api.rpc("agentPresets/list").obj()
                if (generation != epoch) return@launch
                mutable.update { it.copy(presets = c["presets"].array().map { it.obj() }.filter { it.text("broken").isBlank() }.map { Preset(it.text("id"), it.text("name").ifBlank { it.text("id") }, it.text("description"), it.flag("isDefault")) }) }
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }
    private fun process(frame: JsonObject) {
        val stream = frame.text("streamId")
        if (frame.text("type") == "error") {
            val e = frame["error"].obj()
            if (stream == "events") throw HarnessException(e.text("code"), e.text("message"))
            if (stream == followId) mutable.update { it.copy(loading = false, error = e.text("message")) }
            return
        }
        if (frame.text("type") == "end") {
            if (stream == "events") throw HarnessException("stream/ended", "实时事件流结束")
            if (stream == followId) mutable.update { it.copy(loading = false, error = "会话事件流已结束，请重新打开会话") }
            return
        }
        if (frame.text("type") != "item") return
        val v = frame["value"].obj()
        when (stream) {
            "events" -> processEvent(v)
            "control" -> processControl(v)
            "workspaces" -> processWorkspace(v)
            followId -> {
                journal.accept(v)
                val model = journal.projections["modelSelection"].obj()["next"].obj()
                mutable.update { it.copy(messages = journal.messages(), loading = false, hasMore = journal.hasMore,
                    selectedModel = if (v.text("type") == "snapshot") model.ifEmpty { defaultModel } else it.selectedModel,
                    queued = if (v.text("type") == "event" && v["event"].obj().text("type") == "user/message") false else it.queued) }
            }
        }
    }
    private fun processEvent(v: JsonObject) {
        when (v.text("type")) {
            "waterfall" -> {
                val kind = v.text("event")
                val pending = PendingQuestion(v.text("eventId"), v.text("agentId"), kind, v["request"].obj())
                if (kind in listOf("approval/request", "user-questions/request")) {
                    mutable.update { it.copy(pending = it.pending.filterNot { p -> p.eventId == pending.eventId } + pending) }
                } else answer(pending, null, delegate = true)
            }
            "cancel" -> mutable.update { it.copy(pending = it.pending.filterNot { p -> p.eventId == v.text("eventId") }) }
            "emit" -> {
                val args = v["args"].array()
                when (v.text("event")) {
                    "commands/change" -> loadCommands()
                    "api-session/added" -> {
                        val row = SessionSummary.parse(args.firstOrNull().obj())
                        mutable.update { it.copy(sessions = (it.sessions.filterNot { s -> s.id == row.id } + row).sortedByDescending { s -> s.updatedAt }) }
                    }
                    "api-session/removed" -> mutable.update { it.copy(sessions = it.sessions.filterNot { s -> s.id == args.firstOrNull().string() }) }
                    "api-session/status" -> {
                        val id = args.firstOrNull().string(); val running = (args.getOrNull(1) as? JsonPrimitive)?.booleanOrNull ?: false
                        mutable.update { it.copy(sessions = it.sessions.map { s -> if (s.id == id) s.copy(running = running) else s }) }
                    }
                    "api-session/activity" -> {
                        val id = args.firstOrNull().string(); val time = (args.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: 0
                        mutable.update { it.copy(sessions = it.sessions.map { s -> if (s.id == id) s.copy(updatedAt = time) else s }.sortedByDescending { s -> s.updatedAt }) }
                    }
                    "api-session/error" -> if (args.firstOrNull().string() == state.value.selectedId) mutable.update { it.copy(error = args.getOrNull(1).string()) }
                }
            }
        }
    }
    private fun processControl(v: JsonObject) {
        fun applyProjection(id: String, key: String, value: JsonElement?) {
            if (key == "title") mutable.update { it.copy(sessions = it.sessions.map { s -> if (s.id == id && value.string().isNotBlank()) s.copy(title = value.string()) else s }) }
            if (key == "modelSelection" && id == state.value.selectedId) mutable.update { it.copy(selectedModel = value.obj()["next"].obj().ifEmpty { defaultModel }) }
        }
        when (v.text("type")) {
            "baseline" -> v["value"].obj()["projections"].obj().forEach { (id, p) -> p.obj()["values"].obj().forEach { (key, value) -> applyProjection(id, key, value) } }
            "projection" -> applyProjection(v.text("sessionId"), v.text("key"), v["value"])
        }
    }
    private fun processWorkspace(v: JsonObject) {
        when (v.text("type")) {
            "baseline" -> mutable.update { it.copy(workspaces = v["value"].obj()["items"].array().map { Workspace.parse(it.obj()) }, archived = v["value"].obj()["archivedSessionIds"].array().map { it.string() }.toSet()) }
            "upsert" -> { val w = Workspace.parse(v["workspace"].obj()); mutable.update { it.copy(workspaces = it.workspaces.filterNot { old -> old.id == w.id } + w) } }
            "remove" -> mutable.update { it.copy(workspaces = it.workspaces.filterNot { w -> w.id == v.text("workspaceId") }) }
            "archived" -> mutable.update { it.copy(archived = v["archivedSessionIds"].array().map { it.string() }.toSet()) }
        }
    }
    fun selectSession(id: String) {
        if (id == state.value.selectedId && journal.initialized) return
        mutable.update { it.copy(selectedId = id, messages = emptyList(), selectedModel = emptyObject, loading = true, hasMore = false, error = null, queued = false) }
        store.remember(state.value.server, id)
        journal = SessionJournal()
        if (state.value.connected) {
            try { follow(id) }
            catch (e: Exception) {
                // The socket can close between a tap and send(). Keep this UI callback from
                // throwing on the main thread; reconnect will follow the selected session again.
                if (e is CancellationException) throw e
                mutable.update { it.copy(loading = false) }
                reconnect()
            }
        }
    }
    private fun follow(id: String) {
        followId?.let { mux?.cancel(it) }
        journal = SessionJournal()
        val streamId = "session-${UUID.randomUUID()}"; followId = streamId
        mutable.update { it.copy(loading = true) }
        mux?.open(streamId, "session/follow", jsonObject("request" to jsonObject(
            "address" to address(id), "maxMessages" to JsonPrimitive(60), "assistantStream" to JsonPrimitive(true))))
        loadCommands()
    }
    fun loadCommands() {
        val id = state.value.selectedId ?: return
        val api = client ?: return
        mutable.update { it.copy(commands = emptyList(), commandsLoading = true, commandsError = null) }
        viewModelScope.launch {
            try {
                val commands = api.rpc("commands/list", jsonObject("agentId" to str(id))).array().map { SlashCommand.parse(it.obj()) }
                if (api === client && state.value.selectedId == id) mutable.update { it.copy(commands = commands, commandsLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (api === client && state.value.selectedId == id) mutable.update { it.copy(commandsLoading = false, commandsError = e.message ?: "命令列表加载失败") }
            }
        }
    }
    fun clearCommandResult() { mutable.update { it.copy(commandResult = null) } }
    fun cancelExport() { exportJob?.cancel() }
    fun exportSession(server: String?, sessionId: String?, uri: Uri) {
        if (server != state.value.server || sessionId == null || state.value.exporting) return
        val api = client ?: return
        mutable.update { it.copy(exporting = true, exportedBytes = 0, error = null) }
        exportJob = viewModelScope.launch {
            try {
                val execution = api.executeCommand(sessionId, "/export", emptyList())
                val result = execution.obj()["result"].obj()
                if (execution == JsonNull || result.text("kind") == "error") throw HarnessException("export/command", result.text("text").ifBlank { "后端不支持导出命令" })
                api.exportSession(sessionId, {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "w") ?: error("无法写入所选位置")
                }) { bytes -> if (api === client) mutable.update { it.copy(exportedBytes = bytes) } }
                if (api === client) mutable.update { it.copy(commandResult = "会话 ZIP 已保存到所选位置。") }
            } catch (e: Exception) {
                if (api === client) mutable.update { it.copy(error = if (e is CancellationException) "导出已取消，所选位置可能留有未完成的文件。" else connectionFailureMessage(e, "导出失败") + "。所选位置可能留有未完成的文件。") }
            } finally {
                if (api === client) mutable.update { it.copy(exporting = false) }
            }
        }
    }
    fun loadOlder() {
        val id = state.value.selectedId ?: return
        if (!state.value.connected || state.value.loadingOlder || !journal.hasMore) return
        val current = journal; val through = current.cursor; val before = current.firstSeq ?: return
        val api = client ?: return
        mutable.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val page = api.command("session/page", jsonObject("address" to address(id), "throughSeq" to JsonPrimitive(through), "beforeSeq" to JsonPrimitive(before), "maxMessages" to JsonPrimitive(60))).obj()
                if (state.value.selectedId == id && journal === current) { current.prepend(page); mutable.update { it.copy(messages = current.messages(), hasMore = current.hasMore) } }
            } catch (e: Exception) { fail(e) } finally { mutable.update { it.copy(loadingOlder = false) } }
        }
    }
    fun createSession(workspaceId: String?, cwd: String, preset: String?, onCreated: () -> Unit) {
        if (!state.value.connected || state.value.creating) return
        mutable.update { it.copy(creating = true) }
        operate { api ->
            try {
                val v = api.command("session/create", jsonObject("sessionId" to str(UUID.randomUUID().toString()),
                    "workspaceId" to workspaceId?.let(::str), "cwd" to cwd.trim().takeIf { it.isNotBlank() && workspaceId == null }?.let(::str), "agentPreset" to preset?.let(::str))).obj()
                if (api !== client) return@operate
                refresh(api); selectSession(v.text("sessionId")); onCreated()
            } finally { mutable.update { it.copy(creating = false) } }
        }
    }
    fun quickCreateSession() {
        val defaults = state.value.defaults
        createSession(defaults.workspaceId, defaults.cwd, defaults.preset) {}
    }
    fun saveDefaults(value: SessionDefaults) {
        store.saveDefaults(state.value.server, value)
        mutable.update { it.copy(defaults = value) }
    }
    fun setNotifications(enabled: Boolean) {
        store.notifications = enabled
        mutable.update { it.copy(notifications = enabled) }
        if (enabled && state.value.connected) startNotifications()
        else NotificationMonitor.stop(getApplication())
    }
    private fun startNotifications() {
        try { NotificationMonitor.start(getApplication()) }
        catch (_: RuntimeException) {
            store.notifications = false
            mutable.update { it.copy(notifications = false, error = "系统暂时无法启动后台消息服务，请回到应用设置重新开启通知。") }
        }
    }
    fun openNotification(server: String?, sessionId: String?) {
        if (server != state.value.server || sessionId.isNullOrBlank()) return
        store.remember(server, sessionId)
        if (state.value.connected) selectSession(sessionId) else restoreRecent = true
    }
    fun addAttachments(server: String?, sessionId: String?, uris: List<Uri>) {
        if (server != state.value.server || sessionId == null) return
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    val attachment = readAttachment(getApplication<Application>().contentResolver, uri)
                    if (state.value.server != server) return@launch
                    editAttachments(sessionId) { old -> if (old.any { it.uri == uri }) old else old + attachment }
                } catch (e: Exception) { fail(e) }
            }
        }
    }
    private fun editAttachments(sessionId: String, edit: (List<DraftAttachment>) -> List<DraftAttachment>) {
        mutable.update { it.copy(attachmentDrafts = it.attachmentDrafts + (sessionId to edit(it.attachmentDrafts[sessionId].orEmpty()))) }
    }
    fun removeAttachment(id: String) {
        val sessionId = state.value.selectedId ?: return
        if (state.value.sendingSessionId == sessionId) return
        editAttachments(sessionId) { it.filterNot { file -> file.id == id } }
    }
    fun cancelSend() { sendJob?.cancel() }
    suspend fun readImage(sessionId: String, attachmentId: String): ByteArray {
        val api = client ?: throw IllegalStateException("请先连接服务")
        val value = api.command("session/attachment", jsonObject("sessionId" to str(sessionId), "attachmentId" to str(attachmentId))).obj()
        return withContext(Dispatchers.Default) { java.util.Base64.getDecoder().decode(value.text("data")) }
    }
    fun send(text: String, steer: Boolean, onAccepted: () -> Unit) {
        val id = state.value.selectedId ?: return
        val attachments = state.value.attachments.toList()
        val api = client ?: return
        if (!state.value.connected || state.value.sending || (text.isBlank() && attachments.isEmpty())) return
        val commandName = text.trimStart().takeIf { it.startsWith("/") }?.substringAfter('/')?.takeWhile { !it.isWhitespace() }
        val command = commandName?.let { name -> state.value.commands.firstOrNull { it.name == name } }
        if (commandName != null && command == null) {
            mutable.update { it.copy(error = if (it.commandsLoading) "命令列表正在加载，请稍候。" else it.commandsError ?: "未知命令 /$commandName，请从 / 列表中选择。") }
            return
        }
        if (command != null && attachments.isNotEmpty() && !command.acceptsAttachments) {
            mutable.update { it.copy(error = "/${command.name} 不接受附件，请先移除附件。") }; return
        }
        mutable.update { it.copy(sending = true, sendingSessionId = id, error = null) }
        val requestId = UUID.randomUUID().toString()
        activeSendId = requestId
        sendJob = viewModelScope.launch {
            var promptStarted = false
            var currentFile: String? = null
            fun updateFile(fileId: String, transform: (DraftAttachment) -> DraftAttachment) {
                if (api === client && activeSendId == requestId) editAttachments(id) { items -> items.map { if (it.id == fileId) transform(it) else it } }
            }
            try {
                val parts = mutableListOf<PromptPart>()
                val images = mutableMapOf<Int, String>()
                val resolver = getApplication<Application>().contentResolver
                for (attachment in attachments) {
                    currentFile = attachment.id
                    updateFile(attachment.id) { it.copy(error = null) }
                    if (attachment.isImage) {
                        images[parts.size] = attachment.id
                        parts += PromptPart.Image(attachment.source(resolver), attachment.mimeType)
                    } else {
                        val uploaded = attachment.uploaded ?: run {
                            updateFile(attachment.id) { it.copy(status = "正在上传", transferred = 0) }
                            api.uploadFile(id, attachment.source(resolver)) { bytes ->
                                updateFile(attachment.id) { it.copy(transferred = bytes) }
                            }
                        }
                        updateFile(attachment.id) { it.copy(uploaded = uploaded, status = "已上传，待发送", transferred = uploaded.bytes) }
                        parts += PromptPart.Value(jsonObject("type" to str("file"), "receiptId" to str(uploaded.receiptId)))
                    }
                }
                if (command == null && text.isNotBlank()) parts += PromptPart.Value(jsonObject("type" to str("text"), "text" to str(text)))
                currentFile = null
                currentCoroutineContext().ensureActive()
                promptStarted = true
                val progress: (Int, Long) -> Unit = { index, bytes ->
                    images[index]?.let { fileId -> updateFile(fileId) { it.copy(status = "正在发送图片", transferred = bytes) } }
                }
                if (command != null) {
                    val execution = api.executeCommand(id, text.trimStart(), parts, progress)
                    if (execution == JsonNull) throw HarnessException("command/unknown", "服务器没有识别这个命令")
                    val result = execution.obj()["result"].obj()
                    if (result.text("kind") == "error") throw HarnessException("command/failed", result.text("text"))
                    if (api === client) mutable.update { it.copy(commandResult = result.text("text").ifBlank { "/${command.name} 已执行" }) }
                } else api.prompt(jsonObject("requestId" to str(requestId), "sessionId" to str(id),
                    "mode" to str(if (steer) "steer" else "queue"), "clientTimeZone" to str(TimeZone.getDefault().id)), parts, progress)
                if (api !== client) return@launch
                if (drafts[id] == text) drafts[id] = ""
                val sentIds = attachments.map { it.id }.toSet()
                editAttachments(id) { it.filterNot { file -> file.id in sentIds } }
                if (state.value.selectedId == id) { mutable.update { it.copy(queued = command == null && !journal.hasPrompt(requestId)) }; onAccepted() }
            } catch (e: Exception) {
                if (api !== client || activeSendId != requestId) return@launch
                val message = if (e is CancellationException) "已取消" else connectionFailureMessage(e, if (promptStarted) "发送失败" else "上传失败")
                currentFile?.let { fileId -> updateFile(fileId) { it.copy(status = "上传未完成", error = message) } }
                if (e is HarnessException && e.code == "session/attachment-invalid") {
                    editAttachments(id) { list -> list.map { it.copy(uploaded = null, status = "需要重新上传") } }
                }
                mutable.update { it.copy(error = message + if (promptStarted) "。输入和附件已保留；请先确认会话是否已收到消息，再决定是否重发。" else "。输入和附件已保留，可以重试。") }
            } finally {
                if (activeSendId == requestId) {
                    activeSendId = null
                    mutable.update { it.copy(sending = false, sendingSessionId = null) }
                }
            }
        }
    }
    fun cancelTurn() { val id = state.value.selectedId ?: return; operate { it.command("session/cancel", jsonObject("sessionId" to str(id))) } }
    fun rename(title: String) {
        val id = state.value.selectedId ?: return
        if (title.isBlank()) return
        operate { api -> api.command("session/rename", jsonObject("sessionId" to str(id), "title" to str(title))); refresh(api) }
    }
    fun selectModel(model: ModelOption, effort: String?) {
        val id = state.value.selectedId ?: return
        operate { api ->
            val selection = jsonObject("sessionId" to str(id), "provider" to str(model.provider), "model" to str(model.id), "reasoningEffort" to effort?.takeIf { it.isNotBlank() }?.let(::str))
            val value = api.command("session/selectModel", selection).obj()["selected"].obj()
            if (api === client && state.value.selectedId == id) mutable.update { it.copy(selectedModel = value) }
        }
    }
    fun answer(pending: PendingQuestion, value: JsonElement?, delegate: Boolean = false) {
        val cid = clientId ?: return
        if (pending.eventId in state.value.answering) return
        mutable.update { it.copy(answering = it.answering + pending.eventId) }
        operate { api ->
            try {
                api.rpc("\$events/result", jsonObject("clientId" to str(cid), "eventId" to str(pending.eventId),
                    "outcome" to if (delegate) jsonObject("kind" to str("next")) else jsonObject("kind" to str("result"), "value" to value)))
                mutable.update { it.copy(pending = it.pending.filterNot { p -> p.eventId == pending.eventId }) }
            } finally { mutable.update { it.copy(answering = it.answering - pending.eventId) } }
        }
    }
    fun refresh() { operate { refresh(it) } }
    private suspend fun refresh(api: HarnessClient) {
        val rows = api.listSessions()["items"].array().map { SessionSummary.parse(it.obj()) }
        if (api === client) mutable.update { it.copy(sessions = rows) }
    }
    private fun operate(block: suspend (HarnessClient) -> Unit) {
        val api = client ?: return
        if (!state.value.connected) return
        val generation = epoch
        viewModelScope.launch {
            try { block(api) } catch (e: Exception) { if (generation == epoch) fail(e) }
        }
    }
    private fun fail(e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(error = e.message ?: "操作失败，请重试") } }
    private fun address(id: String) = jsonObject("kind" to str("session"), "sessionId" to str(id))
    override fun onCleared() { stop(stopMonitor = false); super.onCleared() }
}
