package io.github.gycrosskit.diagnostics.dingtalk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DingTalkContractTest {
    private val endpoint = "https://oapi.dingtalk.com/robot/send?access_token=fixture"
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
