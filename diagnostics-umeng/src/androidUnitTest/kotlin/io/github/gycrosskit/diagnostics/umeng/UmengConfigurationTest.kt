package io.github.gycrosskit.diagnostics.umeng

import android.content.Context
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure
import com.umeng.umcrash.custommapping.UAPMCustomMapping
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class UmengConfigurationTest {
    @Test
    @Config(shadows = [InitializationShadow::class, AnalyticsShadow::class, CustomMappingShadow::class])
    fun `brand and build environment use searchable dimensions after SDK initialization`() = runBlocking<Unit> {
        for ((brand, environment) in listOf("chonghe" to "qa", "hehekang" to "release")) {
            InitializationShadow.started = false
            CustomMappingShadow.values.clear()
            UmengDiagnosticSdkBridge(RuntimeEnvironment.getApplication()).initialize(
                DiagnosticSdkConfiguration(DiagnosticSdkProvider.UMENG, appKey = "test-key",
                    channel = "distribution", brandId = brand, environment = environment),
                "installation-id", "1.2.3", "42",
            )
            assertTrue(InitializationShadow.started)
            assertEquals(mapOf("s1" to brand, "s2" to environment), CustomMappingShadow.values)
        }
    }

    // 替身阻止真实 SDK 启动和上报，并要求维度只能在初始化返回后设置。
    @Implements(value = UMConfigure::class, callThroughByDefault = false)
    class InitializationShadow {
        companion object {
            var started = false

            @JvmStatic
            @Implementation
            fun __staticInitializer__() {}

            @JvmStatic
            @Implementation
            fun init(context: Context, appKey: String, channel: String, deviceType: Int, pushSecret: String?) {
                started = true
            }
        }
    }

    @Implements(UAPMCustomMapping::class)
    class CustomMappingShadow {
        companion object {
            val values = mutableMapOf<String, String>()

            @JvmStatic
            @Implementation
            fun putStringParam(key: String, value: String): Boolean {
                check(InitializationShadow.started)
                values[key] = value
                return true
            }
        }
    }

    @Implements(value = MobclickAgent::class, callThroughByDefault = false)
    class AnalyticsShadow {
        companion object {
            @JvmStatic
            @Implementation
            fun __staticInitializer__() {}
        }
    }

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
