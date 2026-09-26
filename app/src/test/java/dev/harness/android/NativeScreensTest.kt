package dev.harness.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.harness.android.ui.ConnectionDialog
import dev.harness.android.ui.QuestionDialog
import dev.harness.android.ui.ToolActivity
import dev.harness.android.ui.DraftAttachments
import dev.harness.android.ui.SlashMenu
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
    @Test fun oldToolsStayHiddenUntilHistoryIsExpanded() {
        val tools = listOf(DisplayMessage("t1", "tool", name = "older-search", result = "ok"), DisplayMessage("t2", "tool", name = "latest-test"))
        compose.setContent { HarnessTheme { ToolActivity(tools) } }
        compose.onNodeWithText("latest-test").assertExists()
        compose.onNodeWithText("older-search").assertDoesNotExist()
        compose.onNodeWithText("展开历史工具调用（1）").performClick()
        compose.onNodeWithText("older-search").assertExists()
    }
    @Test fun attachmentRemovalIsDisabledDuringTransfer() {
        val sending = androidx.compose.runtime.mutableStateOf(true)
        var removed: String? = null
        val file = DraftAttachment("file-1", android.net.Uri.parse("content://test/document/1"), "report.pdf", "application/pdf", 1024)
        compose.setContent { HarnessTheme { DraftAttachments(listOf(file), sending.value) { removed = it } } }
        compose.onNodeWithContentDescription("移除 report.pdf").assertIsNotEnabled()
        compose.runOnIdle { sending.value = false }
        compose.onNodeWithContentDescription("移除 report.pdf").performClick()
        assertEquals("file-1", removed)
    }
    @Test fun slashMenuFiltersAndSelectsTheServerCommand() {
        var selected: String? = null
        compose.setContent { HarnessTheme { SlashMenu(listOf(SlashCommand("compact", "压缩上下文", "", false), SlashCommand("goal", "设置目标", "目标内容", true)), "go", false, null, {}) { selected = it.name } } }
        compose.onNodeWithText("/compact").assertDoesNotExist()
        compose.onNodeWithText("/goal").performClick()
        assertEquals("goal", selected)
    }
    @Test fun newConversationDefaultsAreSavedPerServer() {
        val store = SessionStore(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val expected = SessionDefaults("workspace", "/projects/app", "coding")
        store.saveDefaults("http://server-one.test", expected)
        assertEquals(expected, store.defaults("http://server-one.test"))
        assertEquals(SessionDefaults(), store.defaults("http://server-two.test"))
    }
    @Test fun crashReportKeepsStackFramesAndHidesCredentials() {
        val report = sanitizeCrashReport("IOException https://host/?token=secret\nAuthorization: bearer-token\nat dev.harness.android.MainActivity.onCreate(MainActivity.kt:42)")
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("bearer-token"))
        assertTrue(report.contains("MainActivity.kt:42"))
    }
}
