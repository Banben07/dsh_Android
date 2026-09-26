package dev.harness.core

import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class SessionJournalTest {
    private fun snapshot(cursor: Int = 0, records: String = "[]", assistant: String = "{\"revision\":0}") = parseObject("""{"type":"snapshot","header":{"id":"s1"},"cursor":$cursor,"records":$records,"hasMore":false,"projections":{"values":{}},"assistantStream":$assistant}""")
    private fun event(seq: Int, type: String, data: String) = parseObject("""{"type":"event","event":{"seq":$seq,"type":"$type","time":1,"data":$data}}""")
    private fun assistant(json: String) = parseObject("""{"type":"assistant-stream","frame":$json}""")
    @Test fun `streams text and replaces with durable message without duplication`() {
        val journal = SessionJournal(); journal.accept(snapshot())
        journal.accept(assistant("""{"type":"start","revision":1,"attemptId":"a1"}"""))
        journal.accept(assistant("""{"type":"chunk","revision":2,"attemptId":"a1","index":0,"chunk":{"type":"text-delta","index":0,"text":"你好"}}"""))
        assertEquals("你好", journal.messages().single().text)
        journal.accept(event(1, "assistant/message", """{"message":{"content":[{"type":"text","text":"你好"}]}}"""))
        assertEquals(1, journal.messages().size)
        journal.accept(assistant("""{"type":"end","revision":3,"attemptId":"a1","index":1,"outcome":{"kind":"committed","seq":1}}"""))
        assertEquals(1, journal.messages().size)
        assertFalse(journal.messages().single().streaming)
    }
    @Test fun `reconnect baseline replaces old events and restores compact live reasoning`() {
        val journal = SessionJournal(); journal.accept(snapshot())
        journal.accept(event(1, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"old"}]}"""))
        journal.accept(snapshot(5, "[]", """{"revision":8,"activeAttempt":{"attemptId":"new","nextIndex":2,"stream":[{"type":"reasoning-chunks","index":0,"texts":["想","一想"]}]}}"""))
        assertEquals("想一想", journal.messages().single().reasoning)
        assertFalse(journal.messages().any { it.text == "old" })
        journal.accept(assistant("""{"type":"chunk","revision":9,"attemptId":"new","index":2,"chunk":{"type":"text-delta","index":1,"text":"答案"}}"""))
        assertEquals("答案", journal.messages().single().text)
    }
    @Test fun `detects durable and live sequence gaps`() {
        val journal = SessionJournal(); journal.accept(snapshot())
        assertFailsWith<HarnessException> { journal.accept(event(2, "turn/start", "{}")) }
        assertFailsWith<HarnessException> { journal.accept(assistant("""{"type":"start","revision":3,"attemptId":"a1"}""")) }
    }
    @Test fun `duplicates do not duplicate history`() {
        val journal = SessionJournal(); journal.accept(snapshot())
        val event = event(1, "user/message", """{"source":{"kind":"user","rpcId":"p1"},"content":[{"type":"text","text":"test"}]}""")
        journal.accept(event); journal.accept(event)
        assertEquals(1, journal.messages().size)
        assertTrue(journal.hasPrompt("p1"))
    }
    @Test fun `tool result is attached by toolCallId and preserves failure`() {
        val journal = SessionJournal(); journal.accept(snapshot())
        journal.accept(event(1, "tool/call", """{"callId":"t1","name":"bash","arguments":"{\"command\":\"pwd\"}"}"""))
        journal.accept(event(2, "tool/result", """{"message":{"content":[{"type":"tool-result","toolCallId":"t1","isError":true,"content":[{"type":"text","text":"permission denied"}]}]}}"""))
        assertEquals(1, journal.messages().size)
        assertEquals("permission denied", journal.messages().single().result)
        assertTrue(journal.messages().single().isError)
    }
    @Test fun `finalized blocks replace deltas and avoid doubled text`() {
        val journal = SessionJournal(); journal.accept(snapshot())
        journal.accept(assistant("""{"type":"start","revision":1,"attemptId":"a1"}"""))
        journal.accept(assistant("""{"type":"chunk","revision":2,"attemptId":"a1","index":0,"chunk":{"type":"text-delta","index":0,"text":"hello"}}"""))
        journal.accept(assistant("""{"type":"chunk","revision":3,"attemptId":"a1","index":1,"chunk":{"type":"block-end","index":0,"block":{"type":"text","text":"hello"}}}"""))
        assertEquals("hello", journal.messages().single().text)
    }
    @Test fun `structured turn failures are shown to user`() {
        val journal = SessionJournal(); journal.accept(snapshot())
        journal.accept(event(1, "turn/end", """{"reason":{"kind":"error","error":{"message":"model unavailable"}}}"""))
        assertEquals("model unavailable", journal.messages().single().text)
        assertTrue(journal.messages().single().isError)
    }
    @Test fun `older page leaves live cursor unchanged and avoids duplicates`() {
        val journal = SessionJournal(); journal.accept(snapshot(10))
        val older = event(2, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"older"}]}""")
        val page = jsonObject("records" to JsonArray(listOf(older)), "hasMore" to JsonPrimitive(true))
        journal.prepend(page); journal.prepend(page)
        assertEquals(10L, journal.cursor)
        assertEquals(1, journal.messages().size)
        assertTrue(journal.hasMore)
    }
    @Test fun `multiple streaming tools without IDs have unique stable list keys`() {
        val journal = SessionJournal(); journal.accept(snapshot())
        journal.accept(assistant("""{"type":"start","revision":1,"attemptId":"a1"}"""))
        journal.accept(assistant("""{"type":"chunk","revision":2,"attemptId":"a1","index":0,"chunk":{"type":"tool-call-delta","index":0,"name":"read","argumentsDelta":"{}"}}"""))
        journal.accept(assistant("""{"type":"chunk","revision":3,"attemptId":"a1","index":1,"chunk":{"type":"tool-call-delta","index":1,"name":"search","argumentsDelta":"{}"}}"""))
        val keys = journal.messages().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
