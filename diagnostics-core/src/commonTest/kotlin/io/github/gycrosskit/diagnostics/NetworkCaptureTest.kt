package io.github.gycrosskit.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.test.*

class NetworkCaptureTest {
    @Test fun correlatedRequestResponseIsRedactedAndKeepsInitialContext() {
        val store = NetworkLogStore<String>()
        var environment = "qa"
        val output = mutableListOf<String>()
        val capture = NetworkCapture(store, { environment }, captureBody = true,
            redactBody = { redactNetworkJson(it, setOf("name")) }, onLog = output::add,
            allowedHeaders = setOf("Authorization", "Cookie", "Content-Type"))
        val call = assertNotNull(capture.begin("POST", "https://user:secret@example.test/App/SendSMS3?phone=private#secret",
            mapOf("Authorization" to listOf("private-token"), "Cookie" to listOf("private-cookie"), "Content-Type" to listOf("application/json")),
            """{"phone":"private-phone","child":{"name":"private-name"},"ok":true}"""))
        environment = "production"
        call.response(200, mapOf("Set-Cookie" to listOf("private-cookie")), """{"token":"private-token","ok":true}""")
        val records = store.records.value
        assertEquals(listOf(call.requestId, call.requestId), records.map { it.requestId })
        assertEquals(listOf("qa", "qa"), records.map { it.context })
        assertEquals(NetworkLogKind.RESPONSE, records.last().kind)
        assertEquals(200, records.last().statusCode)
        assertTrue(assertNotNull(records.last().durationMillis) >= 0)
        assertEquals(records.map { it.message }, output)
        val text = output.joinToString()
        assertFalse(text.contains("private")); assertFalse(text.contains("secret")); assertFalse(text.contains("user:"))
        assertTrue(text.contains("\"ok\":true")); assertTrue(text.contains("<redacted>"))
    }

    @Test fun bodyIsOptInFailClosedAndByteBounded() {
        val store = NetworkLogStore<String>()
        val body = "{\"ok\":true}"
        NetworkCapture(store, { "qa" }).begin("POST", "https://example.test", emptyMap(), body)
        assertFalse(store.records.value.last().message.contains("BODY:"))
        val capture = NetworkCapture(store, { "qa" }, maxBodyBytes = 16, captureBody = true, redactBody = { it })
        val call = assertNotNull(capture.begin("POST", "https://example.test", emptyMap(), "😀".repeat(5)))
        assertFalse(store.records.value.last().message.contains("BODY:"))
        call.requestBody(body)
        assertTrue(store.records.value.last().message.contains(body))
        val throwing = NetworkCapture(store, { "qa" }, captureBody = true, redactBody = { error("secret") }, onLog = { error("secret") })
        val failed = assertNotNull(throwing.begin("GET", "https://example.test", emptyMap(), body))
        failed.failure(IllegalStateException("private-token"))
        assertFalse(store.records.value.last().message.contains("private-token"))
        assertEquals(NetworkLogKind.FAILURE, store.records.value.last().kind)
        assertNull(redactNetworkJson("{\"token\":\"unfinished"))
        assertNull(redactNetworkJson("[".repeat(65) + "0" + "]".repeat(65)))
        assertNull(NetworkCapture(store, { "qa" }, include = { false }).begin("GET", "https://example.test", emptyMap()))
        assertNull(NetworkCapture(store, { error("secret") }).begin("GET", "https://example.test", emptyMap()))
    }

    @Test fun clearDoesNotReuseIdsAndOldTextInputRemainsUncorrelated() {
        val store = NetworkLogStore<String>(maxRecords = 2)
        val capture = NetworkCapture(store, { "qa" })
        val first = assertNotNull(capture.begin("GET", "https://example.test", emptyMap()))
        store.clear()
        val next = assertNotNull(capture.begin("GET", "https://example.test", emptyMap()))
        assertTrue(next.requestId > first.requestId)
        store.record("REQUEST: https://example.test/legacy", "legacy")
        assertNull(store.records.value.last().requestId)
        assertNull(store.records.value.last().durationMillis)
        next.response(200, emptyMap())
        assertEquals(2, store.records.value.size)
    }

    @Test fun excessiveDepthIsRejectedBeforeParsingAndQuotedBracketsAreText() {
        // 28 KiB 仍在允许的 Body 额度内；旧 parser 会在限深检查前耗尽调用栈。
        assertNull(redactNetworkJson("[".repeat(14_000) + "0" + "]".repeat(14_000)))
        assertNotNull(redactNetworkJson("[".repeat(64) + "0" + "]".repeat(64)))
        assertNotNull(redactNetworkJson("[".repeat(65) + "]".repeat(65)))
        assertNull(redactNetworkJson("[".repeat(66) + "]".repeat(66)))
        val quoted = """{"text":"${"[".repeat(100)}\\\"${"]".repeat(100)}", "token":"private"}"""
        val safe = assertNotNull(redactNetworkJson(quoted))
        assertFalse(safe.contains("private"))
        assertTrue(safe.contains("[".repeat(100)))
    }

    @Test fun synchronousCollectorCanClearStoreWithoutDeadlock() {
        val store = NetworkLogStore<Unit>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var cleared = false
        try {
            scope.launch {
                store.records.collect { records ->
                    if (records.isNotEmpty() && !cleared) {
                        cleared = true
                        store.clear()
                    }
                }
            }
            store.record("REQUEST: https://example.test", Unit)
            assertTrue(cleared)
            assertTrue(store.records.value.isEmpty())
            store.record("REQUEST: https://example.test/next", Unit)
            assertEquals(2L, store.records.value.single().id)
        } finally { scope.cancel() }
    }
}
