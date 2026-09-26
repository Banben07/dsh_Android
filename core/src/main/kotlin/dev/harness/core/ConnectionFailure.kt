package dev.harness.core

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException

/** Preserve the failing stage without exposing URLs or credentials from transport exception messages. */
fun connectionFailureMessage(error: Throwable, stage: String): String {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    val remote = causes.filterIsInstance<HarnessException>().firstOrNull()
    val detail = when {
        remote != null -> "${remote.message ?: "服务拒绝请求"}（${remote.code}）"
        causes.any { it is UnknownHostException } -> "无法解析服务器域名（DNS）"
        causes.any { it is NoRouteToHostException } -> "没有到服务器的网络路由，请确认手机和服务器在互通的网络中"
        causes.any { it is SSLException } -> "HTTPS 握手失败，请确认端口支持 HTTPS，且证书有效并匹配服务器地址"
        causes.any { it is ConnectException } -> "无法连接服务器端口，请确认服务正在监听该地址和端口，且允许手机访问"
        causes.any { it is SocketTimeoutException || it is TimeoutCancellationException } -> "等待服务器响应超时"
        else -> "连接异常（${error.javaClass.simpleName}）"
    }
    return "$stage：$detail"
}
