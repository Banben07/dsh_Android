package dev.harness.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.harness.android.ui.ConnectionDialog
import dev.harness.android.ui.QuestionDialog
import dev.harness.core.*
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectionFormRequiresAnAddressAndPassesTokenExplicitly() {
        var result: Pair<String, String>? = null
        compose.setContent { HarnessTheme { ConnectionDialog(HarnessState(), { address, token -> result = address to token }, {}, {}) } }
        compose.onNodeWithText("连接服务").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("服务地址")).performTextInput("100.80.20.10:3000")
        compose.onNode(hasSetTextAction() and hasText("启动 Token")).performTextInput("test-token")
        compose.onNodeWithText("连接服务").performClick()
        assertEquals("100.80.20.10:3000" to "test-token", result)
    }

    @Test fun approvalOnlySendsAnExplicitSingleUseDecision() {
        var result: JsonElement? = null
        val pending = PendingQuestion("e1", "s1", "approval/request", parseObject("""{"toolName":"bash","reason":"运行测试"}"""))
        compose.setContent { HarnessTheme { QuestionDialog(pending, HarnessState(connection = ConnectionStatus.CONNECTED), {}, { result = it }) } }
        compose.waitForIdle()
        assertNull(result)
        compose.onNodeWithText("仅允许这一次").performClick()
        assertEquals("allowed-once", result.string())
    }

    @Test fun questionsRequireAnAnswerAndKeepBackendQuestionIdentity() {
        var result: JsonElement? = null
        val pending = PendingQuestion("e2", "s1", "user-questions/request", parseObject("""{"questions":[{"id":"branch","question":"选择分支","options":[{"label":"main"},{"label":"dev"}]}]}"""))
        compose.setContent { HarnessTheme { QuestionDialog(pending, HarnessState(connection = ConnectionStatus.CONNECTED), {}, { result = it }) } }
        compose.onNodeWithText("提交回答").assertIsNotEnabled()
        compose.onNodeWithText("main").performClick()
        compose.onNodeWithText("提交回答").performClick()
        val answer = result.obj()["answers"].array().single().obj()
        assertEquals("branch", answer.text("id"))
        assertEquals("main", answer["selected"].array().single().string())
    }
}
