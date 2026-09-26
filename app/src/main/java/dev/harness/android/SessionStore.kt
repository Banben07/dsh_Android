package dev.harness.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.harness.core.*
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

data class SessionDefaults(val workspaceId: String? = null, val cwd: String = "", val preset: String? = null)

/** Only authority-bound cookies are retained. Launch tokens never go to disk. */
class SessionStore(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("harness", Context.MODE_PRIVATE)
    var server: String
        get() = prefs.getString("server", "").orEmpty()
        set(value) { prefs.edit().putString("server", value).apply() }
    fun recent(origin: String) = prefs.getString("recent-${keyFor(origin)}", null)
    fun remember(origin: String, sessionId: String) { prefs.edit().putString("recent-${keyFor(origin)}", sessionId).apply() }
    var notifications: Boolean
        get() = prefs.getBoolean("completion-notifications", false)
        set(value) { prefs.edit().putBoolean("completion-notifications", value).apply() }
    fun defaults(origin: String): SessionDefaults = runCatching {
        val j = parseObject(prefs.getString("defaults-${keyFor(origin)}", "{}").orEmpty())
        SessionDefaults(j.text("workspaceId").ifBlank { null }, j.text("cwd"), j.text("preset").ifBlank { null })
    }.getOrDefault(SessionDefaults())
    fun saveDefaults(origin: String, value: SessionDefaults) {
        val json = jsonObject("workspaceId" to value.workspaceId?.let(::str), "cwd" to str(value.cwd), "preset" to value.preset?.let(::str))
        prefs.edit().putString("defaults-${keyFor(origin)}", json.toString()).apply()
    }
    fun notificationSeq(origin: String, id: String) = prefs.getLong("notice-${keyFor(origin + id)}", -1)
    fun rememberNotification(origin: String, id: String, seq: Long) { prefs.edit().putLong("notice-${keyFor(origin + id)}", seq).apply() }
    private fun origin(url: HttpUrl) = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    private fun keyFor(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val key = "cookies-${keyFor(origin(url))}"
        val merged = loadForRequest(url).associateBy { it.name }.toMutableMap()
        cookies.forEach { cookie -> if (cookie.expiresAt > System.currentTimeMillis()) merged[cookie.name] = cookie else merged.remove(cookie.name) }
        val serialized = JsonArray(merged.values.map { JsonPrimitive(it.toString()) }).toString()
        try { prefs.edit().putString(key, encrypt(serialized)).apply() }
        catch (e: Exception) {
            // CookieJar runs on an OkHttp worker. IOException becomes a failed login; unchecked
            // Keystore errors would otherwise escape that worker and terminate the whole app.
            throw java.io.IOException("无法加密保存登录状态，请解锁设备后重试", e)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val saved = prefs.getString("cookies-${keyFor(origin(url))}", null) ?: return emptyList()
        return try {
            wireJson.parseToJsonElement(decrypt(saved)).array().mapNotNull { Cookie.parse(url, it.string()) }
                .filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        } catch (_: Exception) { emptyList() }
    }
    fun forgetCookies() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("cookies-") }.forEach { edit.remove(it) }
        edit.apply()
    }
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("harness-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("harness-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(text: String): String {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
