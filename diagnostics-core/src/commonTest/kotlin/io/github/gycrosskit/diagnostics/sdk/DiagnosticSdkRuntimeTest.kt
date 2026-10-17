package io.github.gycrosskit.diagnostics.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticSdkRuntimeTest {
    private fun config(provider: String = DiagnosticSdkProvider.BUGLY, key: String = "client-key",
        brand: String = "brand", environment: String = "qa", logs: Boolean = true) =
        DiagnosticSdkConfiguration(provider, "client-id", key, remoteLogs = logs, brandId = brand, environment = environment)
    private fun runtime(config: DiagnosticSdkConfiguration = config(), factory: () -> DiagnosticSdkBridge) =
        DiagnosticSdkRuntime(config, "brand", "qa", "1.2", "3", factory)

    @Test fun openProviderNeedsNoCoreWhitelist() {
        assertTrue(config(provider = "volcengine").matches("brand", "qa"))
        assertFalse(config(provider = "../vendor").matches("brand", "qa"))
    }

    @Test fun noneMissingKeyAndWrongScopeNeverTouchConsentOrFactory() = runTest {
        listOf(config(DiagnosticSdkProvider.NONE), config(key = ""), config(brand = "other"), config(environment = "release"),
            config().copy(crashes = false, remoteLogs = false)).forEach {
            val runtime = runtime(it) { error("Must not construct SDK") }
            runtime.initialize { error("Must not read consent or identity") }
            assertTrue(runtime.state in setOf(DiagnosticSdkState.DISABLED, DiagnosticSdkState.MISSING_CONFIGURATION))
        }
    }

    @Test fun privacyAndConcurrentCallsConstructOnce() = runTest {
        val consent = CompletableDeferred<Unit>()
        val sdk = RecordingBridge()
        var factories = 0
        val runtime = runtime { factories++; sdk }
        val first = async { runtime.initialize { consent.await(); "stable-install-id" } }
        val second = async { runtime.initialize { "other-id" } }
        runCurrent()
        assertEquals(0, factories)
        assertEquals(DiagnosticSdkState.WAITING_FOR_PRIVACY, runtime.state)
        consent.complete(Unit)
        first.await(); second.await()
        assertEquals(1, factories)
        assertEquals(1, sdk.initializations)
        assertEquals("stable-install-id", sdk.deviceId)
        runtime.record("INFO", "LiveEntry", "token=secret Bearer private")
        runtime.record("DEBUG", "KtorHttp", "raw HTTP body")
        backgroundScope.launch { runtime.consumeLogs() }
        runCurrent()
        assertEquals(1, sdk.logs.size)
        assertFalse(sdk.logs.single().contains("secret"))
        assertFalse(sdk.logs.single().contains("private"))
    }

    @Test fun cancellingDuplicateCallerDoesNotCancelActiveInitialization() = runTest {
        val ready = CompletableDeferred<Unit>()
        val runtime = runtime { RecordingBridge() }
        val first = launch { runtime.initialize { ready.await(); "install-id" } }
        runCurrent()
        val second = launch { runtime.initialize { "other-id" } }
        runCurrent(); second.cancel(); runCurrent()
        assertEquals(DiagnosticSdkState.WAITING_FOR_PRIVACY, runtime.state)
        ready.complete(Unit); first.join()
        assertEquals(DiagnosticSdkState.INITIALIZED, runtime.state)
    }

    @Test fun failureDoesNotRetryAndDoesNotForwardLogs() = runTest {
        val sdk = RecordingBridge(IllegalStateException("private key"))
        val runtime = runtime { sdk }
        runtime.initialize { "id" }; runtime.initialize { "id" }
        runtime.record("ERROR", "LiveEntry", "failure")
        assertEquals(DiagnosticSdkState.FAILED, runtime.state)
        assertEquals(1, sdk.initializations)
        assertTrue(sdk.logs.isEmpty())
        val storageFailure = runtime { error("Must not construct SDK") }
        storageFailure.initialize { error("storage unavailable") }
        assertEquals(DiagnosticSdkState.FAILED, storageFailure.state)
    }

    @Test fun nullConsentWaitsAndCancellationPropagates() = runTest {
        val missing = runtime { error("Must not construct SDK") }
        missing.initialize { null }
        assertEquals(DiagnosticSdkState.WAITING_FOR_PRIVACY, missing.state)
        val cancelled = runtime { RecordingBridge(CancellationException("stop")) }
        assertFailsWith<CancellationException> { cancelled.initialize { "id" } }
        val sdk = RecordingBridge()
        val crashOnly = runtime(config(logs = false)) { sdk }
        crashOnly.initialize { "id" }
        crashOnly.record("INFO", "LiveEntry", "message")
        assertEquals(DiagnosticSdkState.INITIALIZED, crashOnly.state)
        assertTrue(sdk.logs.isEmpty())
    }

    @Test fun remoteQueueKeepsOnlyLatest64Entries() = runTest {
        val sdk = RecordingBridge()
        val runtime = runtime { sdk }
        runtime.initialize { "id" }
        repeat(1000) { runtime.record("INFO", "LiveEntry", "stage=$it") }
        backgroundScope.launch { runtime.consumeLogs() }; runCurrent()
        assertEquals(64, sdk.logs.size)
        assertEquals("stage=999", sdk.logs.last())
    }

    @Test fun redactionAndUtf8LimitSurviveQuotedAndTruncatedValues() {
        assertEquals("😀".repeat(1024), safeDiagnosticSdkLog("😀".repeat(3000)))
        assertFalse(config().toString().contains("client-key"))
        listOf("{\"appKey\":\"private-key\"}", "Authorization: Bearer private-key",
            "Authorization: Basic private-key", "{\"password\":\"private-key two words\"}",
            "{\"password\":\"" + "private-key ".repeat(1000)).forEach {
            assertFalse(safeDiagnosticSdkLog(it).contains("private-key"))
        }
        assertTrue(safeDiagnosticSdkLog("token=x ".repeat(1000)).encodeToByteArray().size <= 4096)
        assertFalse(safeDiagnosticSdkLog("{\"password\":\"" + "x".repeat(4082) + "\\").contains("x".repeat(20)))
    }

    private class RecordingBridge(private val fail: Exception? = null) : DiagnosticSdkBridge {
        var initializations = 0
        var deviceId = ""
        val logs = mutableListOf<String>()
        override suspend fun initialize(configuration: DiagnosticSdkConfiguration, deviceId: String, versionName: String, buildNumber: String) {
            initializations++; this.deviceId = deviceId; fail?.let { throw it }
        }
        override fun writeLog(level: String, tag: String, message: String) { logs += message }
    }
}
