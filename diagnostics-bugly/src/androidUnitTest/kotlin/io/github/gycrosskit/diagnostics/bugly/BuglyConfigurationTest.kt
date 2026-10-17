package io.github.gycrosskit.diagnostics.bugly

import io.github.gycrosskit.diagnostics.sdk.AndroidDiagnosticSdkFactory
import java.util.ServiceLoader
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkConfiguration
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkProvider
import com.tencent.bugly.library.BuglyBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class BuglyConfigurationTest {
    @Test
    fun `locked SDK has no performance monitors before initialization`() {
        val builder = crashOnlyBuglyBuilder(
            DiagnosticSdkConfiguration(DiagnosticSdkProvider.BUGLY, appId = "fixture", appKey = "fixture"),
            "installation-id", "1.2.3", "42",
        )
        // 读取锁定 SDK 自己的默认清单，升级新增 monitor 时本用例必须失败。
        val monitors = BuglyBuilder::class.java.declaredFields.single { it.type == List::class.java }
        monitors.isAccessible = true
        assertTrue((monitors.get(builder) as List<*>).isEmpty())
        assertEquals("installation-id", builder.uniqueId)
        assertEquals("1.2.3", builder.appVersion)
        assertEquals("42", builder.buildNumber)
    }

    @Test
    fun `performance request is rejected before SDK initialization`() = runBlocking<Unit> {
        assertFailsWith<UnsupportedOperationException> {
            BuglyDiagnosticSdkBridge(RuntimeEnvironment.getApplication()).initialize(
                DiagnosticSdkConfiguration(DiagnosticSdkProvider.BUGLY, performance = true),
                "installation-id", "1.2.3", "42",
            )
        }
    }

    @Test
    fun `disabled capabilities keep the existing crash handler`() = runBlocking<Unit> {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        BuglyDiagnosticSdkBridge(RuntimeEnvironment.getApplication()).initialize(
            DiagnosticSdkConfiguration(DiagnosticSdkProvider.BUGLY, crashes = false, remoteLogs = false),
            "installation-id", "1.2.3", "42",
        )
        kotlin.test.assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `remote cache is private bounded and has no console output`() {
        val context = RuntimeEnvironment.getApplication()
        val config = remoteLogConfiguration(context)
        assertTrue(config.logPath.startsWith(context.noBackupFilesDir.absolutePath))
        assertEquals(false, config.isConsoleLog)
        assertEquals(5L * 1024 * 1024, config.maxFileSize)
        assertEquals(200L * 1024 * 1024, config.maxAliveFileSize)
        assertEquals(1, config.maxAliveDay)
    }

    @Test
    fun `provider mismatch is rejected before SDK initialization`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> {
            BuglyDiagnosticSdkBridge(RuntimeEnvironment.getApplication()).initialize(
                DiagnosticSdkConfiguration(DiagnosticSdkProvider.UMENG), "installation-id", "1.2.3", "42",
            )
        }
    }

    @Test
    fun `logs before successful initialization do not replace the crash handler`() {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        BuglyDiagnosticSdkBridge(RuntimeEnvironment.getApplication()).writeLog("ERROR", "App", "fixture")
        kotlin.test.assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `published SPI registration creates the provider without initializing its SDK`() {
        val factories = ServiceLoader.load(AndroidDiagnosticSdkFactory::class.java, AndroidDiagnosticSdkFactory::class.java.classLoader).toList()
        assertEquals(listOf(BuglyAndroidDiagnosticSdkFactory::class.java), factories.map { it.javaClass })
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(factories.single().create(RuntimeEnvironment.getApplication()) is BuglyDiagnosticSdkBridge)
        kotlin.test.assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `missing credentials fail before changing handlers or creating SDK cache`() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val directory = context.noBackupFilesDir
        val filesBefore = directory.walkTopDown().map { it.relativeTo(directory).path }.toSet()
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        val configurations = listOf(
            DiagnosticSdkConfiguration(DiagnosticSdkProvider.BUGLY, appId = "", appKey = "fixture"),
            DiagnosticSdkConfiguration(DiagnosticSdkProvider.BUGLY, appId = "fixture", appKey = " "),
        )
        val messages = listOf("Bugly 缺少 appId", "Bugly 缺少 appKey")
        configurations.forEachIndexed { index, configuration ->
            val failure = assertFailsWith<IllegalArgumentException> {
                BuglyDiagnosticSdkBridge(context).initialize(configuration, "installation-id", "1.2.3", "42")
            }
            assertEquals(messages[index], failure.message)
            kotlin.test.assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
            assertEquals(filesBefore, directory.walkTopDown().map { it.relativeTo(directory).path }.toSet())
        }
    }
}
