package dev.harness.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.*
import org.junit.Test

class ConnectionFailureTest {
    @Test fun `server rejection keeps the failing stage and protocol reason`() {
        val message = connectionFailureMessage(HarnessException("gateway/invalid-args", "缺少参数 _request"), "读取会话列表失败")
        assertTrue(message.contains("读取会话列表失败"))
        assertTrue(message.contains("gateway/invalid-args"))
        assertTrue(message.contains("缺少参数 _request"))
    }

    @Test fun `timeout and certificate errors give different explanations`() {
        val timeout = connectionFailureMessage(SocketTimeoutException(), "登录失败")
        val certificate = connectionFailureMessage(SSLHandshakeException("certificate rejected"), "登录失败")
        assertTrue(timeout.contains("超时"))
        assertTrue(certificate.contains("证书"))
        assertNotEquals(timeout, certificate)
    }

    @Test fun `wrapped connection error remains actionable`() {
        val message = connectionFailureMessage(IOException(ConnectException("refused")), "建立实时连接失败")
        assertTrue(message.contains("服务器端口"))
    }

    @Test fun `unknown transport errors never expose request URL or token`() {
        val message = connectionFailureMessage(IOException("GET http://example.test/?token=private-token failed"), "登录失败")
        assertTrue(message.contains("IOException"))
        assertFalse(message.contains("private-token"))
        assertFalse(message.contains("example.test"))
    }
}
