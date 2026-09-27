package dev.harness.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import dev.harness.android.ui.ConnectionDialog
import dev.harness.android.ui.QuestionDialog
import dev.harness.android.ui.SessionActivity
import dev.harness.android.ui.SessionListItem
import dev.harness.android.ui.conversationContent
import dev.harness.android.ui.DraftAttachments
import dev.harness.android.ui.SlashMenu
import dev.harness.android.ui.Markdown
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

    @Test fun conversationRestoresReadingPositionAcrossSessionSwitches() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val server = "http://reading-position.test"
        SessionStore(app).server = server
        val vm = HarnessViewModel(app)
        val messages = (0 until 80).map { DisplayMessage("row-$it", "user", "阅读位置消息 $it") }
        val state = androidx.compose.runtime.mutableStateOf(HarnessState(server = server, selectedId = "a", messages = messages))
        vm.saveReadingPosition(server, "a", HarnessViewModel.ReadingPosition("row-10", 11, 0, false))
        compose.setContent { HarnessTheme { dev.harness.android.ui.Conversation(state.value, vm, androidx.compose.ui.Modifier) } }
        compose.onNodeWithText("阅读位置消息 10").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(15)
        compose.onNodeWithText("阅读位置消息 14").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(selectedId = "b", messages = listOf(DisplayMessage("b", "user", "另一会话"))) }
        compose.onNodeWithText("另一会话").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(selectedId = "a", messages = messages) }
        compose.onNodeWithText("阅读位置消息 14").assertIsDisplayed()
        compose.onNodeWithContentDescription("跳到最新消息").assertIsDisplayed()
        compose.runOnIdle { vm.logout() }
    }

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
    @Test fun reasoningAndToolsShareOneLatestItemAndExpandableHistory() {
        val messages = androidx.compose.runtime.mutableStateOf(listOf(
            DisplayMessage("a1", "assistant", text = "保留答案正文", reasoning = "之前的思考"),
            DisplayMessage("t1", "tool", name = "latest-test", result = "ok"),
        ))
        val initial = conversationContent(messages.value)
        assertEquals(listOf("assistant", "tool"), initial.messages.map { it.kind })
        assertEquals("保留答案正文", initial.messages.first().text)
        assertTrue(initial.messages.first().reasoning.isEmpty())
        compose.setContent { HarnessTheme { SessionActivity(conversationContent(messages.value).activity) } }
        compose.onNodeWithText("latest-test").assertExists()
        compose.onNodeWithText("思考过程").assertDoesNotExist()
        compose.onNodeWithText("展开历史过程（1）").performClick()
        compose.onNodeWithContentDescription("展开思考过程").performClick()
        compose.onNodeWithText("之前的思考").assertExists()
        compose.onNodeWithText("收起历史过程（1）").performClick()
        compose.runOnIdle { messages.value += DisplayMessage("a2", "assistant", reasoning = "新的思考", streaming = true) }
        compose.onNodeWithText("latest-test").assertDoesNotExist()
        compose.onNodeWithText("正在思考").assertExists()
        compose.onNodeWithText("之前的思考").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开思考过程").performClick()
        compose.onNodeWithText("新的思考").assertExists()
        assertEquals(listOf("assistant", "reasoning"), conversationContent(messages.value).messages.map { it.kind })
    }
    @Test fun longPressArchivesAndRestoresWithoutOpeningTheConversation() {
        val archived = androidx.compose.runtime.mutableStateOf(false)
        var opened = 0
        val session = SessionSummary("s1", "测试会话", "/project", 0, false)
        compose.setContent { HarnessTheme { SessionListItem(session, false, archived.value, true, false, { opened++ }, { archived.value = it }) } }
        compose.onNodeWithText("测试会话").performTouchInput { longClick() }
        assertEquals(0, opened)
        assertFalse(archived.value)
        compose.onNodeWithText("归档会话").performClick()
        assertTrue(archived.value)
        compose.onNodeWithText("测试会话").performTouchInput { longClick() }
        compose.onNodeWithText("取消归档").performClick()
        assertFalse(archived.value)
        compose.onNodeWithText("测试会话").performClick()
        assertEquals(1, opened)
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
    @Test fun fontSizeSettingUpdatesNativeMarkdownAndSurvivesReload() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SessionStore(context)
        val scale = androidx.compose.runtime.mutableStateOf(1f)
        lateinit var root: android.view.View
        fun findPreview(view: android.view.View): android.widget.TextView? {
            if (view is android.widget.TextView && view.text.toString() == "字号预览") return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                findPreview(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        compose.setContent {
            HarnessTheme(fontScale = scale.value) {
                root = androidx.compose.ui.platform.LocalView.current.rootView
                androidx.compose.foundation.layout.Column { Markdown("字号预览") }
                ConnectionDialog(HarnessState(fontScale = scale.value), { _, _ -> }, {}, {}, fontScale = {
                    store.fontScale = it
                    scale.value = it
                })
            }
        }
        val original = compose.runOnIdle { checkNotNull(findPreview(root)).textSize }
        compose.onNodeWithContentDescription("字体大小").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        compose.waitForIdle()
        assertEquals(1.5f, SessionStore(context).fontScale)
        val enlarged = compose.runOnIdle { checkNotNull(findPreview(root)).textSize }
        // Android font scaling can be nonlinear; verify the native text actually becomes larger.
        assertTrue("Native text grew from $original to $enlarged", enlarged > original)
        compose.onNodeWithText("恢复默认").performScrollTo().performClick()
        assertEquals(1f, SessionStore(context).fontScale)
    }
}
