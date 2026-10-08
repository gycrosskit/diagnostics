package io.github.gycrosskit.diagnostics

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class AndroidNativeCrashCollectorTest {
    @Test fun boundedNativeHistoryDeduplicatesAcrossAckAndKeepsSameTimestampProcessesDistinct() {
        val root = Files.createTempDirectory("native-crash-history").toFile()
        val store = DiagnosticStore(root.absolutePath)
        var notifications = 0
        val exits = listOf(
            exit(pid = 1, reason = 6) { error("ANR trace must not be read") },
            exit(pid = 2) { ByteArrayInputStream(ByteArray(300_000) { 0xff.toByte() }) },
            exit(pid = 3) { null },
        )
        try {
            collect(store, exits) { notifications++; error("host notification failure") }
            assertEquals(2, store.pendingReportCount())
            assertEquals(2, notifications)
            val reports = store.currentFiles().map { store.readTail(it, 512 * 1024).toString(Charsets.UTF_8) }
            assertTrue(reports.any { "protobuf;base64" in it && "/".repeat(256) in it })
            assertTrue(reports.any { "tombstone=unavailable" in it })
            store.acknowledgeBatch(store.prepareBatch())
            collect(store, exits) { notifications++ }
            assertEquals(0, store.pendingReportCount())
            assertEquals(2, notifications)
            val other = DiagnosticStore(root.resolve("other").absolutePath)
            try { collect(other, exits); assertEquals(2, other.pendingReportCount()) } finally { other.close() }
            assertTrue(root.resolve("android-native-crash-history").length() <= 64 * 65)
        } finally { store.close(); root.deleteRecursively() }
    }

    @Test fun failedSaveIsRetriedAndMissingTraceStillProducesMetadata() {
        val root = Files.createTempDirectory("native-crash-retry").toFile()
        val store = DiagnosticStore(root.absolutePath, DiagnosticLimits(maxReports = 1))
        try {
            store.recordReport(ReportKind.CRASH, "full")
            collect(store, listOf(exit() { error("trace unavailable") }))
            assertFalse(root.resolve("android-native-crash-history").exists())
            store.acknowledgeBatch(store.prepareBatch())
            collect(store, listOf(exit() { null }))
            assertEquals(1, store.pendingReportCount())
        } finally { store.close(); root.deleteRecursively() }
    }

    @Test fun closeDoesNotWaitForBlockedTraceAndDiscardsLateReport() {
        val root = Files.createTempDirectory("native-crash-close").toFile()
        val store = DiagnosticStore(root.absolutePath)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
        val streamClosed = AtomicBoolean(false)
        val stream = object : InputStream() {
            override fun read(): Int = -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                return -1
            }
            override fun close() { streamClosed.set(true); completed.countDown() }
        }
        var notifications = 0
        val collector = AndroidNativeCrashCollector(store, {
            sequence { try { yield(exit { stream }) } finally { completed.countDown() } }
        }, true) { notifications++ }
        try {
            assertTrue(collector.start()); assertTrue(entered.await(5, TimeUnit.SECONDS))
            collector.close()
            assertFalse(collector.start())
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(streamClosed.get()); assertEquals(0, notifications); assertEquals(0, store.pendingReportCount())
        } finally { release.countDown(); collector.close(); store.close(); root.deleteRecursively() }
    }

    @Test fun unsupportedStartAndByteLimitDoNotReadExtraInput() {
        val root = Files.createTempDirectory("native-crash-old-api").toFile()
        val store = DiagnosticStore(root.absolutePath)
        try {
            AndroidNativeCrashCollector(store, { error("old Android") }, false, null).use { assertFalse(it.start()) }
            var read = 0
            val stream = object : InputStream() {
                override fun read(): Int = error("unbounded read")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { read += length; return length }
            }
            assertEquals(17, stream.readBounded(17).size); assertEquals(17, read)
        } finally { store.close(); root.deleteRecursively() }
    }

    private fun collect(store: DiagnosticStore, exits: List<NativeCrashExit>, callback: ((ReportKind) -> Unit)? = null) {
        val completed = CountDownLatch(1)
        AndroidNativeCrashCollector(store, {
            sequence { try { yieldAll(exits) } finally { completed.countDown() } }
        }, true, callback).use { collector ->
            assertTrue(collector.start()); assertTrue(collector.start())
            assertTrue(completed.await(5, TimeUnit.SECONDS))
        }
    }

    private fun exit(pid: Int = 1, reason: Int = 5, trace: () -> InputStream?): NativeCrashExit =
        NativeCrashExit(reason, 1234, pid, "sample:$pid", "fixture native crash", trace)
}
