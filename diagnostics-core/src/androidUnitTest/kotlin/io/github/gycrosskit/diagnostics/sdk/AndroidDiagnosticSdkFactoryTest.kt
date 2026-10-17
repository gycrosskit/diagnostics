package io.github.gycrosskit.diagnostics.sdk

import android.content.Context
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertFailsWith

class AndroidDiagnosticSdkFactoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `one registered factory is discovered without creating its bridge`() {
        val factory = installedAndroidDiagnosticSdkFactory(loader(FirstFactory::class.java.name))
        assertEquals(FirstFactory::class.java, factory.javaClass)
    }

    @Test
    fun `missing factory fails with a fixed safe message`() {
        val failure = assertFailsWith<IllegalStateException> { installedAndroidDiagnosticSdkFactory(loader("")) }
        assertEquals("未安装诊断 SDK 适配组件", failure.message)
    }

    @Test
    fun `multiple factories fail before either bridge is created`() {
        val failure = assertFailsWith<IllegalStateException> {
            installedAndroidDiagnosticSdkFactory(loader(FirstFactory::class.java.name, SecondFactory::class.java.name))
        }
        assertEquals("只能安装一个诊断 SDK 适配组件", failure.message)
    }

    @Test
    fun `invalid service registration does not expose its original error`() {
        val failure = assertFailsWith<IllegalStateException> {
            installedAndroidDiagnosticSdkFactory(loader("private.invalid.Factory"))
        }
        assertEquals("诊断 SDK 适配组件注册无效", failure.message)
        assertEquals(null, failure.cause)
    }

    @Test
    fun `factory constructor failure does not expose its original error`() {
        val failure = assertFailsWith<IllegalStateException> {
            installedAndroidDiagnosticSdkFactory(loader(FailingFactory::class.java.name))
        }
        assertEquals("诊断 SDK 适配组件注册无效", failure.message)
        assertEquals(null, failure.cause)
    }

    private fun loader(vararg names: String): ClassLoader {
        val registration = temporaryFolder.newFile().apply { writeText(names.joinToString("\n")) }
        return object : ClassLoader(AndroidDiagnosticSdkFactory::class.java.classLoader) {
            override fun getResources(name: String): Enumeration<URL> =
                if (name == "META-INF/services/" + AndroidDiagnosticSdkFactory::class.java.name)
                    Collections.enumeration(listOf(registration.toURI().toURL()))
                else super.getResources(name)
        }
    }

    class FirstFactory : AndroidDiagnosticSdkFactory {
        override fun create(context: Context): DiagnosticSdkBridge = error("查找时不能创建桥接实例")
    }

    class SecondFactory : AndroidDiagnosticSdkFactory {
        override fun create(context: Context): DiagnosticSdkBridge = error("多个工厂时不能创建桥接实例")
    }

    class FailingFactory : AndroidDiagnosticSdkFactory {
        init { error("不可暴露的厂商初始化内容") }
        override fun create(context: Context): DiagnosticSdkBridge = error("不可调用")
    }
}
