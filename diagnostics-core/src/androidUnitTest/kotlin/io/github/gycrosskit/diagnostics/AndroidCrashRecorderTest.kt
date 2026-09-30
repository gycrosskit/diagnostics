package io.github.gycrosskit.diagnostics

import java.nio.file.Files
import kotlin.test.*

class AndroidCrashRecorderTest {
    @Test fun duplicateInstallIsRejectedAndCloseRestoresExistingHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val downstream = Thread.UncaughtExceptionHandler { _, _ -> error("测试不触发崩溃") }
        val root = Files.createTempDirectory("android-recorder-lifecycle").toFile()
        val store = DiagnosticStore(root.absolutePath)
        var recorder: AndroidCrashRecorder? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler(downstream)
            recorder = AndroidCrashRecorder(store)
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertNotSame(downstream, installed)
            assertFailsWith<IllegalStateException> { AndroidCrashRecorder(store) }
            assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
            recorder.close()
            assertSame(downstream, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            recorder?.close()
            Thread.setDefaultUncaughtExceptionHandler(previous)
            store.close()
            root.deleteRecursively()
        }
    }
}
