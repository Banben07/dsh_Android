package dev.harness.android

import android.app.Application
import android.content.Context
import android.os.Build

class HarnessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = packageManager.getPackageInfo(packageName, 0).versionName
                val report = "DeepSeek Harness $version\nAndroid ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}\n" + error.stackTraceToString()
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("lastCrash", sanitizeCrashReport(report).take(24_000)).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

fun lastCrashReport(context: Context): String? = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).getString("lastCrash", null)
fun sanitizeCrashReport(text: String): String = text
    .replace(Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE), "<服务地址>")
    .replace(Regex("(?i)(token|cookie|authorization|api[_-]?key)([\\s:=]+)[^\\s,;]+"), "$1$2<已隐藏>")
