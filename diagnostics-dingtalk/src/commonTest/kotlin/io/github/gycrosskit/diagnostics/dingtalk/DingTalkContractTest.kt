package io.github.gycrosskit.diagnostics.dingtalk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DingTalkContractTest {
    private val endpoint = "https://oapi.dingtalk.com/robot/send?access_token=fixture"
    @Test fun closingOneWrapperDoesNotCloseBorrowedTransportOrAnotherWrapper() = runTest {
        var calls = 0
        val transport = HttpClient(MockEngine { calls++; respond("""{"errcode":0}""", HttpStatusCode.OK) })
        val first = DingTalkWebhookClient(endpoint, "test-secret", transport)
        val second = DingTalkWebhookClient(endpoint, "test-secret", transport)
        try {
            first.close(); first.close()
            assertEquals(DingTalkSendStatus.CLOSED, first.send("title", "text").status)
            assertTrue(second.send("title", "text").success)
            assertEquals(1, calls)
        } finally { first.close(); second.close(); transport.close() }
    }

    @Test fun invalidEndpointFailsWithoutTransportAndPayloadPreservesEscapedText() = runTest {
        var calls = 0
        val transport = HttpClient(MockEngine { calls++; error("invalid endpoint must not reach transport") })
        val client = DingTalkWebhookClient(endpoint.replace("oapi.dingtalk.com", "evil.test"), "test-secret", transport)
        try { assertEquals(DingTalkSendStatus.TRANSPORT_FAILURE, client.send("title", "text").status) }
        finally { client.close(); transport.close() }
        assertEquals(0, calls)
        val title = "quoted \"title\""
        val markdown = "first\n\\second"
        val body = kotlinx.serialization.json.Json.parseToJsonElement(encodeDingTalkMarkdownPayload(title, markdown))
        val payload = body as kotlinx.serialization.json.JsonObject
        val content = payload["markdown"] as kotlinx.serialization.json.JsonObject
        assertEquals(title, (content["title"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(markdown, (content["text"] as kotlinx.serialization.json.JsonPrimitive).content)
        for (malformed in listOf("{}", "[]", "null", """{"errcode":{}}""", """{"errcode":"bad"}""", """{"errcode":"0"}""")) {
            assertEquals(DingTalkSendStatus.INVALID_RESPONSE, decodeDingTalkWebhookResponse(malformed).status)
        }
    }
    @Test fun signatureAndOfficialEndpointValidation() {
        val url = Url(signedDingTalkWebhook(endpoint, "test-secret", 1700000000000))
        assertEquals("1700000000000", url.parameters["timestamp"])
        assertEquals("BYMqUCZnSqbfPf1GCfZftO7Rg2g6P+Rp3/4+bLNtSGA=", url.parameters["sign"])
        for (invalid in listOf(endpoint.replace("https", "http"), endpoint.replace("oapi.dingtalk.com", "evil.test"),
            endpoint.replace("/robot/send", "/other"), endpoint.replace("oapi.dingtalk.com", "oapi.dingtalk.com:444"),
            endpoint.replace("https://", "https://user@"), "$endpoint&sign=old", "$endpoint#fragment")) {
            assertFailsWith<IllegalArgumentException> { signedDingTalkWebhook(invalid, "test-secret", 1) }
        }
    }
    @Test fun rfc4231HmacVectorsIncludeLongKey() {
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).toHexString())
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hmacSha256(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()).toHexString())
    }
    @Test fun multibyteResponseLimitIsInBytes() = runTest {
        val start = """{"errcode":0,"padding":"""" + "中".repeat(5400)
        val suffix = "\"}"
        val body = start + "x".repeat(16 * 1024 + 1 - start.encodeToByteArray().size - suffix.length) + suffix
        assertEquals(16 * 1024 + 1, body.encodeToByteArray().size)
        val transport = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) })
        val client = DingTalkWebhookClient(endpoint, "test-secret", transport)
        try { assertEquals(DingTalkSendStatus.INVALID_RESPONSE, client.send("x", "x").status) }
        finally { client.close(); transport.close() }
    }
    @Test fun fakeResponseAndCloseAndConfigurationNeverSendRealMessages() = runTest {
        var calls = 0
        val transport = HttpClient(MockEngine { request ->
            calls++
            assertEquals("oapi.dingtalk.com", request.url.host)
            respond("""{"errcode":0,"errmsg":"ok"}""", HttpStatusCode.OK)
        })
        val client = DingTalkWebhookClient(endpoint, "test-secret", transport) { 1 }
        try {
            assertTrue(client.send("title", "markdown").success)
            client.close()
            assertEquals(DingTalkSendStatus.CLOSED, client.send("x", "x").status)
            val missing = DingTalkWebhookClient("", "", transport)
            assertEquals(DingTalkSendStatus.NOT_CONFIGURED, missing.send("x", "x").status)
            missing.close(); assertEquals(1, calls)
        } finally { client.close(); transport.close() }
    }
    @Test fun redirectsFailuresAndOversizedResponsesAreBounded() = runTest {
        for ((code, body, expected) in listOf(
            Triple(HttpStatusCode.TemporaryRedirect, "redirect", DingTalkSendStatus.HTTP_FAILURE),
            Triple(HttpStatusCode.InternalServerError, "server failure", DingTalkSendStatus.HTTP_FAILURE),
            Triple(HttpStatusCode.OK, "bad json", DingTalkSendStatus.INVALID_RESPONSE),
            Triple(HttpStatusCode.OK, "x".repeat(20000), DingTalkSendStatus.INVALID_RESPONSE),
            Triple(HttpStatusCode.OK, """{"errcode":310000,"errmsg":"private response"}""", DingTalkSendStatus.REJECTED))) {
            var calls = 0
            val transport = HttpClient(MockEngine { calls++; respond(body, code, headersOf(HttpHeaders.Location, "https://evil.test")) })
            val client = DingTalkWebhookClient(endpoint, "test-secret", transport)
            try { val result = client.send("x", "x"); assertEquals(expected, result.status); assertEquals("", result.message); assertEquals(1, calls) }
            finally { client.close(); transport.close() }
        }
    }
    @Test fun transportFailureDoesNotExposeCredentialsAndCancellationPropagates() = runTest {
        for (cancel in listOf(false, true)) {
            val transport = HttpClient(MockEngine { if (cancel) throw CancellationException("cancel") else throw IllegalStateException("secret fixture") })
            val client = DingTalkWebhookClient(endpoint, "test-secret", transport)
            try {
                if (cancel) assertFailsWith<CancellationException> { client.send("x", "x") }
                else assertEquals(DingTalkSendStatus.TRANSPORT_FAILURE, client.send("x", "x").status)
            } finally { client.close(); transport.close() }
        }
    }
}
