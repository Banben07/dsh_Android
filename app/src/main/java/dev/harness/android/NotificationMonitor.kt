package dev.harness.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.harness.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Foreground service keeps the process available for session sockets and optional completion events. */
class NotificationMonitor : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var origin: String? = null
    private var monitoringNotifications = false
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CONNECTION_CHANNEL, "后台消息连接", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(REPLY_CHANNEL, "回复完成", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = connectionNotification("正在连接消息服务")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(ONGOING_ID, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SessionStore(this)
        if (!store.keepBackgroundConnection || store.server.isBlank()) { stopSelf(); return START_NOT_STICKY }
        val shouldMonitor = store.notifications
        if (job?.isActive == true && origin == store.server && monitoringNotifications == shouldMonitor) return START_STICKY
        job?.cancel(); origin = store.server
        monitoringNotifications = shouldMonitor
        job = scope.launch {
            if (shouldMonitor) monitor(store.server, store) else keepProcessAlive()
        }
        return START_STICKY
    }
    private suspend fun keepProcessAlive() {
        manager.notify(ONGOING_ID, connectionNotification("后台保持应用连接"))
        while (currentCoroutineContext().isActive) delay(15_000)
    }
    private fun openApp(sessionId: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("server", origin).putExtra("sessionId", sessionId)
        return PendingIntent.getActivity(this, sessionId?.hashCode() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun connectionNotification(text: String) = Notification.Builder(this, CONNECTION_CHANNEL)
        .setSmallIcon(dev.harness.android.R.drawable.ic_deepseek).setContentTitle("DeepSeek Harness")
        .setContentText(text).setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_SERVICE).build()
    private suspend fun monitor(server: String, store: SessionStore) {
        val tracker = CompletionTracker()
        var titles = emptyMap<String, String>()
        while (currentCoroutineContext().isActive) {
            try {
                HarnessClient(ServerAddress.parse(server), store).use { api ->
                    api.mux().use { mux ->
                        val first = withTimeout(20_000) { mux.frames.receive() }
                        val ready = first["value"].obj()
                        if (first.text("streamId") != "events" || ready.text("type") != "ready") throw HarnessException("protocol/ready", "消息服务握手失败")
                        val pending = mutableMapOf<String, String>()
                        fun inspect(id: String) {
                            if (!store.notifications) return
                            if (id in pending.values) return
                            val stream = "notice-${UUID.randomUUID()}"; pending[stream] = id
                            mux.open(stream, "session/follow", jsonObject("request" to jsonObject(
                                "address" to jsonObject("kind" to str("session"), "sessionId" to str(id)),
                                "maxMessages" to JsonPrimitive(5), "assistantStream" to JsonPrimitive(false))))
                        }
                        val sessions = api.listSessions()["items"].array().map { SessionSummary.parse(it.obj()) }
                        titles = sessions.filterNot { it.isChild }.associate { it.id to it.title }
                        tracker.baseline(sessions).forEach(::inspect)
                        manager.notify(ONGOING_ID, connectionNotification("后台保持与服务器连接"))
                        for (frame in mux.frames) {
                            val stream = frame.text("streamId")
                            if (frame.text("type") in listOf("error", "end")) {
                                if (stream == "events") throw HarnessException("stream/closed", "消息连接已结束")
                                pending.remove(stream); continue
                            }
                            if (frame.text("type") != "item") continue
                            val v = frame["value"].obj()
                            if (stream == "events") {
                                if (v.text("type") == "emit") {
                                    val args = v["args"].array(); val id = args.firstOrNull().string()
                                    when (v.text("event")) {
                                        "api-session/added" -> {
                                            val row = SessionSummary.parse(args.firstOrNull().obj())
                                            if (!row.isChild) { titles = titles + (row.id to row.title); tracker.status(row.id, row.running) }
                                        }
                                        "api-session/status" -> if (id in titles && tracker.status(id, (args.getOrNull(1) as? JsonPrimitive)?.booleanOrNull == true)) inspect(id)
                                    }
                                }
                            } else if (v.text("type") == "snapshot") {
                                val id = pending.remove(stream) ?: continue
                                mux.cancel(stream)
                                val reply = completedReply(v) ?: continue
                                if (reply.seq <= store.notificationSeq(server, id)) continue
                                store.rememberNotification(server, id, reply.seq)
                                if (store.notifications && !appVisible && manager.areNotificationsEnabled()) {
                                    val title = v["projections"].obj()["values"].obj().text("title").ifBlank { titles[id] ?: "会话" }
                                    val notice = Notification.Builder(this@NotificationMonitor, REPLY_CHANNEL)
                                        .setSmallIcon(dev.harness.android.R.drawable.ic_deepseek).setContentTitle("回复完成 · $title")
                                        .setContentText(reply.preview).setStyle(Notification.BigTextStyle().bigText(reply.preview))
                                        .setContentIntent(openApp(id)).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                                        .setCategory(Notification.CATEGORY_MESSAGE).build()
                                    manager.notify("reply-$server-$id", 2, notice)
                                }
                            }
                        }
                        throw HarnessException("stream/closed", "消息连接已断开")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                manager.notify(ONGOING_ID, connectionNotification("消息连接中断，正在重连"))
            }
            delay(5_000)
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val CONNECTION_CHANNEL = "background-connection"
        private const val REPLY_CHANNEL = "completed-replies"
        private const val ONGOING_ID = 1
        @Volatile var appVisible = false
        fun start(context: Context) { context.startForegroundService(Intent(context, NotificationMonitor::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, NotificationMonitor::class.java)) }
    }
}
