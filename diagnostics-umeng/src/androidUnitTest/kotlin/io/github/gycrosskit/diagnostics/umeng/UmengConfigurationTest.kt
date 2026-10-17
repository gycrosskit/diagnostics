package io.github.gycrosskit.diagnostics.umeng

import io.github.gycrosskit.diagnostics.sdk.AndroidDiagnosticSdkFactory
import java.util.ServiceLoader
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkConfiguration
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkProvider
import com.umeng.umcrash.UMCrash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFailsWith
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class UmengConfigurationTest {
    @Test
    fun `core provides Android Main dispatcher before vendor initialization`() {
        assertFalse(Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext))
    }

    @Test
    fun `remote log chunks retain emoji on the SDK length boundary`() {
        val message = "a".repeat(1023) + "😀" + "b".repeat(1023) + "😀"
        val parts = umengLogChunks(message)
        assertEquals(message, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 1024 })
        assertTrue(parts.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }

    @Test
    fun `remote log identity and crash flags exist before SDK initialization`() {
        val config = crashOnlyUmengConfiguration(DiagnosticSdkConfiguration(DiagnosticSdkProvider.UMENG), "installation-id")
        assertTrue(config.getBoolean(UMCrash.KEY_ENABLE_CRASH_JAVA))
        assertTrue(config.getBoolean(UMCrash.KEY_ENABLE_CRASH_NATIVE))
        assertTrue(config.getBoolean(UMCrash.KEY_ENABLE_ANR))
        assertTrue(config.getBoolean(UMCrash.KEY_ENABLE_CODE_LOG))
        assertEquals("installation-id", config.getString(UMCrash.KEY_LOG_USER_ID))
        val enabled = config.keySet().filter { config.get(it) == true }.toSet()
        assertEquals(setOf(UMCrash.KEY_ENABLE_CRASH_JAVA, UMCrash.KEY_ENABLE_CRASH_NATIVE,
            UMCrash.KEY_ENABLE_ANR, UMCrash.KEY_ENABLE_CODE_LOG), enabled)
    }

    @Test
    fun `remote log collection remains off when disabled`() {
        assertFalse(crashOnlyUmengConfiguration(DiagnosticSdkConfiguration(DiagnosticSdkProvider.UMENG, remoteLogs = false), "installation-id").getBoolean(UMCrash.KEY_ENABLE_CODE_LOG))
    }

    @Test
    fun `crash monitoring can be disabled before SDK initialization`() {
        val config = crashOnlyUmengConfiguration(
            DiagnosticSdkConfiguration(DiagnosticSdkProvider.UMENG, crashes = false), "installation-id",
        )
        UMCrash.initConfig(config)
        listOf("enableJavaLog", "enableNativeLog", "enableANRLog").forEach { name ->
            val field = UMCrash::class.java.getDeclaredField(name)
            field.isAccessible = true
            assertFalse(field.getBoolean(null))
        }
        assertTrue(config.getBoolean(UMCrash.KEY_ENABLE_CODE_LOG))
    }

    @Test
    fun `performance request is rejected before SDK initialization`() = runBlocking<Unit> {
        assertFailsWith<UnsupportedOperationException> {
            UmengDiagnosticSdkBridge(RuntimeEnvironment.getApplication()).initialize(
                DiagnosticSdkConfiguration(DiagnosticSdkProvider.UMENG, performance = true),
                "installation-id", "1.2.3", "42",
            )
        }
    }

    @Test
    fun `all capabilities disabled keeps the existing crash handler`() = runBlocking<Unit> {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        UmengDiagnosticSdkBridge(RuntimeEnvironment.getApplication()).initialize(
            DiagnosticSdkConfiguration(DiagnosticSdkProvider.UMENG, crashes = false, remoteLogs = false),
            "installation-id", "1.2.3", "42",
        )
        kotlin.test.assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `published SPI registration creates the provider without initializing its SDK`() {
        val factories = ServiceLoader.load(AndroidDiagnosticSdkFactory::class.java, AndroidDiagnosticSdkFactory::class.java.classLoader).toList()
        assertEquals(listOf(UmengAndroidDiagnosticSdkFactory::class.java), factories.map { it.javaClass })
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(factories.single().create(RuntimeEnvironment.getApplication()) is UmengDiagnosticSdkBridge)
        kotlin.test.assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `missing credentials fail before changing handlers or creating SDK cache`() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val directory = context.noBackupFilesDir
        val filesBefore = directory.walkTopDown().map { it.relativeTo(directory).path }.toSet()
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        val configurations = listOf(DiagnosticSdkConfiguration(DiagnosticSdkProvider.UMENG, appKey = " "))
        val messages = listOf("友盟缺少 appKey")
        configurations.forEachIndexed { index, configuration ->
            val failure = assertFailsWith<IllegalArgumentException> {
                UmengDiagnosticSdkBridge(context).initialize(configuration, "installation-id", "1.2.3", "42")
            }
            assertEquals(messages[index], failure.message)
            kotlin.test.assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
            assertEquals(filesBefore, directory.walkTopDown().map { it.relativeTo(directory).path }.toSet())
        }
    }
}
