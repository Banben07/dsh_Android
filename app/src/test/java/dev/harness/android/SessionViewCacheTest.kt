package dev.harness.android

import dev.harness.core.DisplayMessage
import dev.harness.core.emptyObject
import org.junit.Assert.*
import org.junit.Test

class SessionViewCacheTest {
    private fun view(text: String) = SessionViewCache.View(listOf(DisplayMessage("key", "assistant", text)), emptyObject, false)
    @Test fun keepsRecentViewsWithinTheCountAndTextBudget() {
        val cache = SessionViewCache()
        repeat(6) { cache.put("$it", view("message $it")) }
        assertNotNull(cache["0"])
        cache.put("6", view("message 6"))
        assertNull(cache["1"])
        assertNotNull(cache["0"])
        cache.put("large-a", view("x".repeat(1_100_000)))
        cache.put("large-b", view("y".repeat(1_100_000)))
        assertNull(cache["large-a"])
        assertNotNull(cache["large-b"])
        cache.put("oversize", view("z".repeat(2_000_001)))
        assertNull(cache["oversize"])
        assertNotNull(cache["large-b"])
    }
}
