package dev.harness.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ServerAddress(val base: HttpUrl, val launchToken: String?) {
    val origin: String get() = base.toString().removeSuffix("/")
    companion object {
        fun parse(input: String, token: String = ""): ServerAddress {
            val raw = input.trim()
            require(raw.isNotEmpty()) { "请输入服务器 IP:端口或 HTTP/HTTPS 地址" }
            val url = (if ("://" in raw) raw else "http://$raw").toHttpUrlOrNull()
                ?: throw IllegalArgumentException("地址格式无效，例如 http://192.168.1.10:3000 或 https://harness.example.com")
            require(url.username.isEmpty() && url.password.isEmpty()) { "地址不能包含用户名或密码" }
            require(url.encodedPath in listOf("/", "/index.html")) { "请填写 harness 的根地址，不要包含 /api 或页面路径" }
            val suppliedToken = token.trim().ifBlank { url.queryParameter("token").orEmpty() }.ifBlank { null }
            return ServerAddress(url.newBuilder().encodedPath("/").query(null).fragment(null).build(), suppliedToken)
        }
    }
}
