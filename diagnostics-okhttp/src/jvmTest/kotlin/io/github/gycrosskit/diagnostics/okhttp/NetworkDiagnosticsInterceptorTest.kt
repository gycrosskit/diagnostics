package io.github.gycrosskit.diagnostics.okhttp

import io.github.gycrosskit.diagnostics.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import okio.buffer
import kotlin.test.*

class NetworkDiagnosticsInterceptorTest {
    private fun client(capture: NetworkCapture<*>) = OkHttpClient.Builder()
        .addInterceptor(NetworkDiagnosticsInterceptor(capture)).build()

    @Test fun jsonIsObservedDuringNormalConsumptionAndCredentialsNeverReachEitherSink() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setHeader("Set-Cookie", "private-cookie")
                .setBody("""{"token":"private-token","ok":true}"""))
            val store = NetworkLogStore<String>()
            val output = mutableListOf<String>()
            val capture = NetworkCapture(store, { "qa" }, captureBody = true, redactBody = { redactNetworkJson(it) }, onLog = output::add)
            val sent = """{"phone":"private-phone","ok":true}"""
            val request = Request.Builder().url(server.url("/App/SendSMS3?secret=private-query"))
                .header("Authorization", "private-auth").post(sent.toRequestBody("application/json".toMediaType())).build()
            client(capture).newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertFalse(store.records.value.any { it.kind == NetworkLogKind.RESPONSE && it.message.contains("BODY:") })
                assertEquals("""{"token":"private-token","ok":true}""", response.body!!.string())
            }
            assertEquals(sent, server.takeRequest().body.readUtf8())
            assertEquals(1, store.records.value.map { it.requestId }.distinct().size)
            assertTrue(store.records.value.last().durationMillis!! >= 0)
            assertEquals(store.records.value.map { it.message }, output)
            assertFalse(output.joinToString().contains("private"))
            assertTrue(output.joinToString().contains("\"ok\":true"))
        }
    }

    @Test fun oneShotBodyIsWrittenOnceAndOversizedOrBinaryBodyIsOmitted() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("😀".repeat(20)))
            var writes = 0
            val sent = """{"phone":"private"}"""
            val body = object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = sent.toByteArray().size.toLong()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { assertEquals(1, ++writes); sink.writeUtf8(sent) }
            }
            val store = NetworkLogStore<String>()
            val capture = NetworkCapture(store, { "qa" }, maxBodyBytes = 8, captureBody = true, redactBody = { it })
            client(capture).newCall(Request.Builder().url(server.url("/")).post(body).build()).execute().use { it.body!!.string() }
            assertEquals(1, writes)
            assertEquals(sent, server.takeRequest().body.readUtf8())
            assertFalse(store.records.value.any { it.message.contains("BODY:") })
            server.enqueue(MockResponse().setBody("ok"))
            var closingWrites = 0
            val closingBody = object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = 2L
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { closingWrites++; sink.use { it.writeUtf8("{}") } }
            }
            client(capture).newCall(Request.Builder().url(server.url("/")).post(closingBody).build()).execute().use { assertEquals("ok", it.body!!.string()) }
            assertEquals(1, closingWrites)
            assertEquals("{}", server.takeRequest().body.readUtf8())
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("binary"))
            client(capture).newCall(Request.Builder().url(server.url("/")).post("binary".toRequestBody("application/octet-stream".toMediaType())).build())
                .execute().use { assertEquals("binary", it.body!!.string()) }
            assertFalse(store.records.value.takeLast(2).any { it.message.contains("BODY:") })
        }
    }

    @Test fun defaultCaptureDoesNotReadOrWaitForResponseAndEarlyCloseDoesNotPublishBody() {
        val store = NetworkLogStore<String>()
        var reads = 0
        val capture = NetworkCapture(store, { "qa" }, captureBody = true, redactBody = { it })
        val source = object : okio.Source {
            override fun timeout() = okio.Timeout.NONE
            override fun read(sink: okio.Buffer, byteCount: Long): Long { reads++; error("must not pre-read") }
            override fun close() = Unit
        }
        val http = client(capture).newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(object : ResponseBody() {
                    private val buffered = source.buffer()
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = 2L
                    override fun source() = buffered
                }).build()
        }.build()
        http.newCall(Request.Builder().url("https://example.test").build()).execute().close()
        assertEquals(0, reads)
        assertEquals(2, store.records.value.size)
        assertFalse(store.records.value.any { it.message.contains("BODY:") })
    }

    @Test fun binarySseMalformedUtf8AndDisabledCapturePreserveOriginalBytes() {
        MockWebServer().use { server ->
            val store = NetworkLogStore<String>()
            val capture = NetworkCapture(store, { "qa" }, captureBody = true, redactBody = { it })
            val http = client(capture)
            for (type in listOf("text/event-stream", "application/octet-stream", "application/json;charset=iso-8859-1")) {
                server.enqueue(MockResponse().setHeader("Content-Type", type).setBody("payload"))
                http.newCall(Request.Builder().url(server.url("/")).build()).execute().use { assertEquals("payload", it.body!!.string()) }
            }
            val malformed = byteArrayOf(0xc3.toByte(), 0x28)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(okio.Buffer().write(malformed)))
            http.newCall(Request.Builder().url(server.url("/")).build()).execute().use { assertContentEquals(malformed, it.body!!.bytes()) }
            assertFalse(store.records.value.any { it.message.contains("BODY:") })
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
            client(NetworkCapture(store, { "qa" })).newCall(Request.Builder().url(server.url("/")).post("{}".toRequestBody("application/json".toMediaType())).build())
                .execute().use { assertEquals("{}", it.body!!.string()) }
            assertFalse(store.records.value.any { it.message.contains("BODY:") })
        }
    }

    @Test fun networkFailurePropagatesAndDiagnosticFailuresDoNotBreakCalls() {
        val store = NetworkLogStore<String>()
        val expected = java.io.IOException("private-secret-url")
        val http = client(NetworkCapture(store, { "qa" }, onLog = { error("log-failure") })).newBuilder()
            .addInterceptor { throw expected }.build()
        val actual = assertFailsWith<java.io.IOException> { http.newCall(Request.Builder().url("https://example.test").build()).execute() }
        assertSame(expected, actual)
        assertEquals(NetworkLogKind.FAILURE, store.records.value.last().kind)
        assertFalse(store.records.value.last().message.contains("private-secret"))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            client(NetworkCapture(store, { error("private-secret") })).newCall(Request.Builder().url(server.url("/")).build())
                .execute().use { assertEquals("ok", it.body!!.string()) }
            assertEquals(2, store.records.value.size)
        }
    }
}
