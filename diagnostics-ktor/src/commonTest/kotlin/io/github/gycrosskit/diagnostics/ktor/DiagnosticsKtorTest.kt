package io.github.gycrosskit.diagnostics.ktor

import io.github.gycrosskit.diagnostics.NetworkCapture
import io.github.gycrosskit.diagnostics.NetworkLogKind
import io.github.gycrosskit.diagnostics.NetworkLogStore
import io.github.gycrosskit.diagnostics.redactNetworkJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.isSaved
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.ContentConverter
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.charsets.Charset
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosticsKtorTest {
    @Test fun savedJsonIsRedactedAssociatedAndStillReplayable() = runTest {
        val store = NetworkLogStore<String>()
        val request = "{\"token\":\"request-secret\",\"name\":\"allowed\"}"
        val response = "{\"password\":\"response-secret\",\"ok\":true}"
        val client = HttpClient(MockEngine { data ->
            assertEquals("Bearer header-secret", data.headers[HttpHeaders.Authorization])
            assertEquals(request, data.body.toByteArray().decodeToString())
            respond(response, HttpStatusCode.Created, textHeaders(response, "application/json"))
        }) {
            install(DiagnosticsKtor) {
                capture = NetworkCapture(store, { "test" }, captureBody = true, redactBody = { redactNetworkJson(it) })
            }
        }
        try {
            val result = client.post("https://user:user-secret@example.test/item?key=query-secret") {
                headers.append(HttpHeaders.Authorization, "Bearer header-secret")
                setBody(TextContent(request, ContentType.Application.Json))
            }
            assertEquals(HttpStatusCode.Created, result.status)
            assertEquals(response, result.bodyAsText())
            assertEquals(response, result.bodyAsText())
            val records = store.records.value
            assertEquals(3, records.size)
            assertEquals(1, records.map { it.requestId }.toSet().size)
            assertTrue(records.all { it.context == "test" })
            val metadata = records.first { it.kind == NetworkLogKind.RESPONSE }
            assertNotNull(metadata.durationMillis)
            assertTrue(metadata.durationMillis!! >= 0)
            val logs = records.joinToString { it.message }
            for (secret in listOf("request-secret", "response-secret", "header-secret", "query-secret", "user-secret")) {
                assertFalse(logs.contains(secret), secret)
            }
            assertTrue(logs.contains("allowed"))
            assertTrue(logs.contains("<redacted>"))
        } finally { client.close() }
    }

    @Test fun bodyRequiresOptInAndHostRedactionAndUsesUtf8ByteLimit() = runTest {
        for ((enabled, redactor, limit) in listOf(
            Triple(false, { value: String -> value }, 100),
            Triple(true, { _: String -> null }, 100),
            Triple(true, { value: String -> value }, 3),
        )) {
            val store = NetworkLogStore<Unit>()
            val body = "四字"
            val client = HttpClient(MockEngine { respond(body, headers = textHeaders(body)) }) {
                install(DiagnosticsKtor) {
                    capture = NetworkCapture(store, { Unit }, captureBody = enabled, redactBody = redactor, maxBodyBytes = limit)
                }
            }
            try {
                assertEquals(body, client.post("https://example.test/") {
                    setBody(TextContent(body, ContentType.Text.Plain))
                }.bodyAsText())
                assertEquals(2, store.records.value.size)
                assertTrue(store.records.value.none { it.message.contains("BODY:") || it.message.contains(body) })
            } finally { client.close() }
        }
    }

    @Test fun byteArrayTextIsObservedWithoutChangingRequest() = runTest {
        val store = NetworkLogStore<Unit>()
        val client = HttpClient(MockEngine { data ->
            assertEquals("known", data.body.toByteArray().decodeToString())
            respond("", headers = textHeaders(""))
        }) {
            install(DiagnosticsKtor) { capture = NetworkCapture(store, { Unit }, captureBody = true, redactBody = { it }) }
        }
        try {
            client.post("https://example.test/") { setBody(ByteArrayContent("known".encodeToByteArray(), ContentType.Text.Plain)) }
            assertTrue(store.records.value.first().message.contains("BODY:\nknown"))
        } finally { client.close() }
    }

    @Test fun customOneShotAndChannelRequestsAreOnlyConsumedByEngine() = runTest {
        var bytesCalls = 0
        var reads = 0
        var writes = 0
        val body = "one-shot"
        val customBytes = object : OutgoingContent.ByteArrayContent() {
            override val contentType = ContentType.Text.Plain
            override val contentLength = body.length.toLong()
            override fun bytes(): ByteArray { assertEquals(1, ++bytesCalls); return body.encodeToByteArray() }
        }
        val readChannel = object : OutgoingContent.ReadChannelContent() {
            override val contentType = ContentType.Text.Plain
            override val contentLength = body.length.toLong()
            override fun readFrom(): ByteReadChannel { assertEquals(1, ++reads); return ByteReadChannel(body) }
        }
        val writeChannel = object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Text.Plain
            override val contentLength = body.length.toLong()
            override suspend fun writeTo(channel: ByteWriteChannel) { assertEquals(1, ++writes); channel.writeStringUtf8(body) }
        }
        val store = NetworkLogStore<Unit>()
        val client = HttpClient(MockEngine { data ->
            assertEquals(body, data.body.toByteArray().decodeToString())
            respond("")
        }) {
            install(DiagnosticsKtor) { capture = NetworkCapture(store, { Unit }, captureBody = true, redactBody = { it }) }
        }
        try {
            for (content in listOf(customBytes, readChannel, writeChannel)) {
                client.post("https://example.test/") { setBody(content) }
            }
            assertEquals(listOf(1, 1, 1), listOf(bytesCalls, reads, writes))
            assertTrue(store.records.value.none { it.message.contains("BODY:") })
        } finally { client.close() }
    }

    @Test fun binaryEncodedAndNonUtf8ResponsesSkipBody() = runTest {
        val store = NetworkLogStore<Unit>()
        val engine = MockEngine { data ->
            val headers = when (data.url.encodedPath) {
                "/binary" -> textHeaders("hidden", "application/octet-stream")
                "/charset" -> textHeaders("hidden", "text/plain; charset=iso-8859-1")
                else -> headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ContentEncoding to listOf("gzip"))
            }
            respond("hidden", headers = headers)
        }
        val client = HttpClient(engine) {
            install(DiagnosticsKtor) { capture = NetworkCapture(store, { Unit }, captureBody = true, redactBody = { it }) }
        }
        try {
            for (path in listOf("binary", "charset", "encoded")) {
                assertEquals("hidden", client.get("https://example.test/$path").bodyAsText())
            }
            client.post("https://example.test/binary") {
                setBody(ByteArrayContent("hidden".encodeToByteArray(), ContentType.Application.OctetStream))
            }
            client.post("https://example.test/binary") {
                setBody(ByteArrayContent(byteArrayOf(0xC3.toByte(), 0x28), ContentType.Text.Plain))
            }
            assertTrue(store.records.value.none { it.message.contains("hidden") || it.message.contains("BODY:") })
        } finally { client.close() }
    }

    @Test fun savedJsonWithoutContentLengthUsesActualBytesAndRemainsReplayable() = runTest {
        val store = NetworkLogStore<Unit>()
        val small = "{\"token\":\"small-secret\",\"ok\":true}"
        val oversized = "{\"token\":\"oversize-secret\",\"text\":\"${"四".repeat(40)}\"}"
        val client = HttpClient(MockEngine { data ->
            respond(if (data.url.encodedPath == "/small") small else oversized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(DiagnosticsKtor) {
                capture = NetworkCapture(store, { Unit }, maxBodyBytes = 64, captureBody = true, redactBody = { redactNetworkJson(it) })
            }
        }
        try {
            for ((path, body) in listOf("small" to small, "large" to oversized)) {
                val response = client.get("https://example.test/$path")
                assertTrue(response.isSaved)
                assertEquals(null, response.headers[HttpHeaders.ContentLength])
                assertEquals(body, response.bodyAsText())
                assertEquals(body, response.bodyAsText())
            }
            val bodyRecords = store.records.value.filter { it.message.contains("BODY:") }
            assertEquals(1, bodyRecords.size)
            assertEquals("https://example.test/small", bodyRecords.single().url)
            assertTrue(bodyRecords.single().message.contains("\"ok\":true"))
            assertTrue(store.records.value.none { it.message.contains("small-secret") || it.message.contains("oversize-secret") })
        } finally { client.close() }
    }

    @OptIn(InternalAPI::class)
    @Test fun streamingResponseDoesNotWaitForEofOrReadBody() = runTest {
        for (enabled in listOf(false, true)) {
            val store = NetworkLogStore<Unit>()
            val channel = ByteChannel(autoFlush = true)
            channel.writeStringUtf8("pending")
            // MockEngine 默认使用真实 IO dispatcher；虚拟 timeout 必须与 engine 使用同一调度器。
            val engine = MockEngine(MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { respond(channel, headers = headersOf(HttpHeaders.ContentType, "text/plain")) }
            })
            val client = HttpClient(engine) {
                if (enabled) install(DiagnosticsKtor) {
                    capture = NetworkCapture(store, { Unit }, captureBody = true, redactBody = { it })
                }
            }
            try {
                withTimeout(3000) {
                    client.prepareGet("https://example.test/").execute { response ->
                        assertEquals(HttpStatusCode.OK, response.status)
                        assertEquals(if (enabled) 2 else 0, store.records.value.size)
                        assertFalse(response.isSaved)
                        assertFalse(channel.isClosedForRead)
                        assertTrue(response.rawContent === channel)
                        // Ktor 的 DefaultTransformers 为 body<ByteReadChannel>() 创建复制 channel，裸客户端也如此。
                        val original = response.body<ByteReadChannel>()
                        assertEquals('p'.code.toByte(), original.readByte())
                        assertTrue(store.records.value.none { it.message.contains("BODY:") })
                    }
                }
            } finally { channel.cancel(null); client.close() }
        }
    }

    @Test fun originalExceptionsAndCancellationPropagateWithoutSecretMessages() = runTest {
        for (cause in listOf(IllegalStateException("exception-secret"), CancellationException("cancel-secret"))) {
            val store = NetworkLogStore<Unit>()
            suspend fun receiveFailure(enabled: Boolean): Throwable {
                val client = HttpClient(MockEngine { throw cause }) {
                    if (enabled) install(DiagnosticsKtor) { capture = NetworkCapture(store, { Unit }) }
                }
                return try {
                    val actual = try { client.get("https://example.test/"); null } catch (caught: Throwable) { caught }
                    assertNotNull(actual)
                } finally { client.close() }
            }
            val baseline = receiveFailure(false)
            val actual = receiveFailure(true)
            // JVM 协程恢复 stacktrace 时可复制异常；插件须保持裸 Ktor 的类型、消息和原 cause 链。
            assertEquals(baseline::class, actual::class)
            assertEquals(baseline.message, actual.message)
            assertTrue(generateSequence(actual) { it.cause }.any { it === cause })
            if (cause is CancellationException) assertTrue(actual is CancellationException)
            assertEquals(NetworkLogKind.FAILURE, store.records.value.last().kind)
            assertTrue(store.records.value.none { it.message.contains("-secret") })
        }
    }

    @Test fun malformedContentLengthKeepsKtorFailureAndOmitsBody() = runTest {
        val store = NetworkLogStore<Unit>()
        val client = HttpClient(MockEngine { respond("mismatch-secret", headers = textHeaders("x")) }) {
            install(DiagnosticsKtor) { capture = NetworkCapture(store, { Unit }, maxBodyBytes = 4, captureBody = true, redactBody = { it }) }
        }
        try {
            assertFailsWith<IllegalStateException> { client.get("https://example.test/") }
            assertTrue(store.records.value.any { it.kind == NetworkLogKind.RESPONSE })
            assertEquals(NetworkLogKind.FAILURE, store.records.value.last().kind)
            assertTrue(store.records.value.none { it.message.contains("mismatch-secret") || it.message.contains("BODY:") })
        } finally { client.close() }
    }

    @Test fun redirectsAndConcurrentCallsHaveIndependentIds() = runTest {
        val store = NetworkLogStore<Unit>()
        val client = HttpClient(MockEngine { data ->
            if (data.url.encodedPath == "/redirect") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://example.test/final"))
            } else {
                respond(data.url.encodedPath, headers = textHeaders(data.url.encodedPath))
            }
        }) {
            install(DiagnosticsKtor) { capture = NetworkCapture(store, { Unit }) }
        }
        try {
            val results = listOf("redirect", "other").map { path ->
                async { client.get("https://example.test/$path").bodyAsText() }
            }.awaitAll()
            assertEquals(listOf("/final", "/other"), results)
            val calls = store.records.value.groupBy { it.requestId }
            assertEquals(3, calls.size)
            calls.values.forEach { records ->
                assertEquals(listOf(NetworkLogKind.REQUEST, NetworkLogKind.RESPONSE), records.map { it.kind })
                assertEquals(1, records.map { it.url }.toSet().size)
            }
        } finally { client.close() }
    }

    @Test fun requestBodyIsObservedAfterContentNegotiation() = runTest {
        val store = NetworkLogStore<Unit>()
        var serializations = 0
        val converter = object : ContentConverter {
            override suspend fun serialize(contentType: ContentType, charset: Charset, typeInfo: TypeInfo, value: Any?): OutgoingContent {
                serializations++
                return TextContent("{\"value\":1}", contentType)
            }
            override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? = null
        }
        val client = HttpClient(MockEngine { data ->
            assertEquals("{\"value\":1}", data.body.toByteArray().decodeToString())
            respond("")
        }) {
            install(DiagnosticsKtor) { capture = NetworkCapture(store, { Unit }, captureBody = true, redactBody = { it }) }
            install(ContentNegotiation) { register(ContentType.Application.Json, converter) }
        }
        try {
            client.post("https://example.test/") { contentType(ContentType.Application.Json); setBody(listOf(1)) }
            assertEquals(1, serializations)
            assertTrue(store.records.value.first().message.contains("BODY:\n{\"value\":1}"))
        } finally { client.close() }
    }
}

private fun textHeaders(body: String, type: String = "text/plain"): Headers = headersOf(
    HttpHeaders.ContentType to listOf(type),
    HttpHeaders.ContentLength to listOf(body.encodeToByteArray().size.toString()),
)
