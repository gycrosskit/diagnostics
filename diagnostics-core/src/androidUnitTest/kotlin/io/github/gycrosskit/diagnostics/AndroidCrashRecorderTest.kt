package io.github.gycrosskit.diagnostics

import java.nio.file.Files
import kotlin.test.*

class AndroidCrashRecorderTest {
    @Test fun onlyStoredReportsNotifyAndDetachedHandlerCannotReviveRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val root = Files.createTempDirectory("android-recorder-notify").toFile()
        val store = DiagnosticStore(root.absolutePath, DiagnosticLimits(maxReports = 1))
        var downstreamCalls = 0
        var notifications = 0
        var recorder: AndroidCrashRecorder? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> downstreamCalls++ }
            recorder = AndroidCrashRecorder(store) { kind ->
                assertEquals(ReportKind.CRASH, kind)
                assertEquals(1, store.pendingReportCount())
                notifications++
            }
            val handler = checkNotNull(Thread.getDefaultUncaughtExceptionHandler())
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("mock"))
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("capacity full"))
            assertEquals(1, notifications)
            recorder.close()
            store.acknowledgeBatch(store.prepareBatch())
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("late"))
            assertEquals(0, store.pendingReportCount())
            assertEquals(1, notifications)
            assertEquals(3, downstreamCalls)
        } finally {
            recorder?.close()
            Thread.setDefaultUncaughtExceptionHandler(previous)
            store.close()
            root.deleteRecursively()
        }
    }

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
