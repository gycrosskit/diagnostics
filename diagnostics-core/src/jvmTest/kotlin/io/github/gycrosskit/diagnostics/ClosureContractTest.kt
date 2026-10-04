package io.github.gycrosskit.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.test.*

class ClosureContractTest {
    @Test fun legacyFrozenBatchSurvivesRestartAndOnlyFullAckDeletesMatchingOriginals() {
        val directory = Files.createTempDirectory("diagnostics-legacy").toFile()
        val legacy = File(directory, "old-pending").apply { mkdirs() }
        File(legacy, "batch-id").writeText("historical-id")
        val original = File(legacy, "crash_1.txt").apply { writeText("older crash") }
        val active = File(directory, "old-active").apply { mkdirs() }
        File(active, "app_0.log").writeText("active")
        val sources = listOf(LegacyDiagnosticSource(legacy.path, listOf("crash_", "app_"), frozen = true), LegacyDiagnosticSource(active.path, listOf("app_")))
        var store = DiagnosticStore(File(directory, "current").path, legacySources = sources)
        try {
            val batch = store.prepareBatch()
            assertEquals("historical-id", batch.id)
            assertEquals(1, batch.files.size)
            assertTrue(original.exists()) // no implicit ack after read/cancel
            val reader = store.openFile(batch, batch.files.single().id)
            assertEquals("older crash", reader.read().decodeToString()); reader.close()
            store.close()
            store = DiagnosticStore(File(directory, "current").path, legacySources = sources)
            val restored = store.prepareBatch()
            assertEquals(batch.id, restored.id)
            original.writeText("a newer crash")
            store.acknowledgeBatch(restored)
            assertTrue(original.exists()) // changed source must remain
            val retry = store.prepareBatch()
            store.acknowledgeBatch(retry)
            assertFalse(original.exists())
            assertFalse(File(legacy, "batch-id").exists())
            assertTrue(store.prepareBatch().files.single().name.contains("app_0.log"))
        } finally { store.close(); directory.deleteRecursively() }
    }
    @Test fun capacityFailureDoesNotSplitOldFrozenBatchOrDeleteSources() {
        val directory = Files.createTempDirectory("diagnostics-capacity").toFile()
        val legacy = File(directory, "legacy").apply { mkdirs() }
        File(legacy, "crash_a").writeText("a"); File(legacy, "crash_b").writeText("b")
        val store = DiagnosticStore(File(directory, "current").path, DiagnosticLimits(maxBatchFiles = 1), listOf(LegacyDiagnosticSource(legacy.path, listOf("crash_"), frozen = true)))
        try { assertFails { store.prepareBatch() }; assertEquals(2, legacy.listFiles()!!.size) }
        finally { store.close(); directory.deleteRecursively() }
    }
    @Test fun nonFrozenSnapshotTailAndZipRetainSameNameAndDoNotRotate() {
        val directory = Files.createTempDirectory("diagnostics-read").toFile()
        val store = DiagnosticStore(File(directory, "current").path)
        try {
            store.append("before"); store.prepareBatch(); store.append("new😀\nlast")
            val files = store.currentFiles()
            assertEquals(2, files.size); assertEquals(1, files.map { it.name }.distinct().size)
            val current = files.first { !it.path.contains("/pending/") }
            assertEquals("last\n", store.readTail(current, 6, true).decodeToString())
            val zip = File(directory, "out.zip")
            exportDiagnosticZip(zip.path, files.mapIndexed { index, file -> DiagnosticZipFile("$index/${file.name}", file) }, mapOf("metadata.json" to "{}"))
            ZipFile(zip).use { assertEquals(3, it.size()) }
            assertEquals(files, store.currentFiles())
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", diagnosticFingerprint("abc".encodeToByteArray()))
            val large = File(directory, "large").apply { writeBytes(ByteArray(200000) { it.toByte() }) }
            val file = DiagnosticSnapshotFile(large.path, large.name, large.length())
            assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(large.readBytes()).joinToString("") { "%02x".format(it) }, DiagnosticFiles.fingerprint(file))
        } finally { store.close(); directory.deleteRecursively() }
    }
    @Test fun writerFlushReportsStickyFailure() {
        val directory = Files.createTempDirectory("diagnostics-writer").toFile()
        val store = DiagnosticStore(directory.path)
        val writer = DiagnosticWriter(store)
        writer.append("line"); writer.flush(); assertEquals(1, store.currentFiles().size)
        store.close(); writer.append("must fail")
        assertFailsWith<java.io.IOException> { writer.flush() }
        assertFailsWith<java.io.IOException> { writer.close() }
        directory.deleteRecursively()
    }
    @Test fun flushTimeoutAndInterruptedBarrierPreserveSourcesAndInterruptFlag() {
        val queue = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        queue.execute { started.countDown(); release.await() }; started.await()
        try {
            assertFailsWith<java.io.IOException> { awaitDiagnosticFlush(queue, 10) }
            Thread.currentThread().interrupt()
            assertFailsWith<java.io.IOException> { awaitDiagnosticFlush(queue, 5000) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted(); release.countDown(); queue.shutdownNow() }
    }
    @Test fun throwableReportKeepsUnicodeCauseSuppressedAnd512KiBBudget() {
        val throwable = IllegalStateException("top", IllegalArgumentException("cause"))
        throwable.addSuppressed(IllegalStateException("suppressed"))
        val report = boundedThrowableReport(Thread.currentThread(), throwable, 0)
        assertTrue(report.contains("1970-01-01T00:00:00.000Z"))
        assertTrue(report.contains("Caused by:")); assertTrue(report.contains("Suppressed:"))
        val huge = boundedThrowableReport(Thread.currentThread(), IllegalStateException("😀".repeat(200000)), 0)
        assertTrue(huge.encodeToByteArray().size <= 512 * 1024)
        assertTrue(huge.endsWith("[CRASH REPORT TRUNCATED]\n")); assertFalse(huge.contains('�'))
    }
    @Test fun concurrentNetworkRecordsKeepIncreasingIdsAndBoundedCopy() {
        val store = NetworkLogStore<String>()
        val executor = Executors.newFixedThreadPool(4)
        val finished = CountDownLatch(4)
        repeat(4) { worker -> executor.execute { repeat(25) { store.record("REQUEST: https://example.test/$worker/$it", "test") }; finished.countDown() } }
        finished.await(); executor.shutdown()
        val records = store.records.value
        assertEquals((1L..100L).toList(), records.map { it.id })
        store.record("RESPONSE: 404 Not Found\nFROM: https://example.test/path?x=1\n" + "x".repeat(40000), "production")
        val record = store.records.value.last()
        assertEquals(100, store.records.value.size); assertEquals(404, record.statusCode)
        assertEquals("https://example.test/path?x=1", record.url)
        assertTrue(record.truncated); assertEquals(32 * 1024, record.message.length)
        store.clear(); assertTrue(store.records.value.isEmpty())
    }
}
