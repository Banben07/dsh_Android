package dev.harness.android

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recent connection lifecycle events, kept on the device so background disconnects can be diagnosed. */
object ConnectionLog {
    private const val KEY = "connectionLog"
    private const val MAX_LINES = 150
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized fun record(context: Context, event: String) {
        val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val lines = prefs.getString(KEY, null).orEmpty().lines().filter { it.isNotBlank() }.takeLast(MAX_LINES - 1)
        val line = "${time.format(Date())} ${sanitizeCrashReport(event).replace('\n', ' ')}"
        prefs.edit().putString(KEY, (lines + line).joinToString("\n")).apply()
    }

    fun report(context: Context): String? {
        val log = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return "DeepSeek Harness $version\nAndroid ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}\n$log"
    }
}
