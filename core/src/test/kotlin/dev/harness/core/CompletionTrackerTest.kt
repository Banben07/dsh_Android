package dev.harness.core

import kotlin.test.*
import org.junit.Test

class CompletionTrackerTest {
    private fun row(id: String, running: Boolean) = SessionSummary(id, id, "/tmp", 0, running)
    @Test fun `idle initial history never generates notifications and repeated stop is ignored`() {
        val tracker = CompletionTracker()
        assertTrue(tracker.baseline(listOf(row("old", false))).isEmpty())
        assertFalse(tracker.status("old", false))
        assertFalse(tracker.status("active", true))
        assertTrue(tracker.status("active", false))
        assertFalse(tracker.status("active", false))
    }
    @Test fun `completion while reconnecting is detected once`() {
        val tracker = CompletionTracker()
        tracker.baseline(listOf(row("s1", true)))
        assertEquals(setOf("s1"), tracker.baseline(listOf(row("s1", false))))
        assertTrue(tracker.baseline(listOf(row("s1", false))).isEmpty())
    }
    @Test fun `cancelled and failed turns are not successful completion notices`() {
        fun snapshot(kind: String) = parseObject("""{"records":[{"type":"event","event":{"seq":8,"type":"assistant/message","data":{"turn":1,"message":{"content":[{"type":"text","text":"完成内容"}]}}}},{"type":"event","event":{"seq":9,"type":"turn/end","data":{"turn":1,"reason":{"kind":"$kind"}}}}]}""")
        assertNull(completedReply(snapshot("aborted")))
        assertNull(completedReply(snapshot("error")))
        assertEquals(CompletedReply(9, "完成内容"), completedReply(snapshot("completed")))
    }
}
