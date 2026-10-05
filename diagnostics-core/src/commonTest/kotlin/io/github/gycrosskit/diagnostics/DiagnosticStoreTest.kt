package io.github.gycrosskit.diagnostics

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem as fs
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.*

class DiagnosticStoreTest {
    private val small = DiagnosticLimits(maxLogBytes = 64, maxLogFiles = 2, maxReportBytes = 64, maxReports = 2)

    @Test fun rollingAndUtf8NeverExceedLimits() = inDirectory { root ->
        val store = DiagnosticStore(root.toString(), small)
        store.append("A".repeat(55)); store.append("B".repeat(55)); store.append("C".repeat(55))
        assertEquals(setOf("log_0.txt", "log_1.txt"), fs.list(root).filter { it.name.startsWith("log_") }.map { it.name }.toSet())
        assertTrue(read(Path(root, "log_1.txt")).startsWith("B"))
        store.append("😀".repeat(100))
        val bytes = fs.source(Path(root, "log_0.txt")).buffered().use { it.readByteArray() }
        assertTrue(bytes.size <= 64)
        assertFalse(bytes.decodeToString().contains('\uFFFD'))
        assertTrue(bytes.decodeToString().contains("[truncated]"))
        store.close()
    }

    @Test fun stableRetryAndDeleteOnlyExportedBatch() = inDirectory { base ->
        val root = Path(base, "private")
        val exports = Path(base, "exports")
        val store = DiagnosticStore(root.toString(), small)
        store.append("old log")
        assertTrue(store.recordReport(ReportKind.CRASH, "original crash"))
        val batch = store.prepareBatch()
        store.append("new log")
        assertTrue(store.recordReport(ReportKind.HANG, "new hang"))
        val retry = store.prepareBatch()
        assertEquals(batch.id, retry.id); assertEquals(batch.files, retry.files)
        val exported = store.exportBatch(batch, exports.toString())
        store.deleteExported(exported)
        assertEquals("new log\n", read(Path(root, "log_0.txt")))
        assertEquals(1, store.pendingReportCount())
        val next = store.prepareBatch()
        assertNotEquals(batch.id, next.id)
        assertFails { store.deleteExported(exported) }
        assertEquals(2, next.files.size)
        store.close()
    }

    @Test fun corruptedArchiveAndExportFailurePreserveSource() = inDirectory { base ->
        val root = Path(base, "private")
        val store = DiagnosticStore(root.toString(), small)
        store.append("retain")
        val batch = store.prepareBatch()
        val invalidDestination = Path(base, "file")
        fs.sink(invalidDestination).buffered().use { it.writeString("file") }
        assertFails { store.exportBatch(batch, invalidDestination.toString()) }
        assertEquals(batch.files, store.prepareBatch().files)
        val export = store.exportBatch(batch, Path(base, "exports").toString())
        fs.sink(Path(export.path)).buffered().use { it.writeString("corrupt") }
        assertFails { store.deleteExported(export) }
        assertEquals(batch.id, store.prepareBatch().id)
        assertEquals("retain\n", read(Path(root, "pending", batch.files.single().name)))
        assertFails { store.exportBatch(batch, Path(root, "nested").toString()) }
        store.close()
    }

    @Test fun restartReportsAndCapacityBoundaries() = inDirectory { base ->
        val root = Path(base, "private")
        val store = DiagnosticStore(root.toString(), small)
        assertTrue(store.recordReport(ReportKind.CRASH, "one"))
        val batch = store.prepareBatch()
        assertTrue(store.recordReport(ReportKind.HANG, "two"))
        assertFalse(store.recordReport(ReportKind.SYSTEM, "overflow"))
        store.close()
        assertFails { store.append("closed") }
        val constrained = DiagnosticStore(root.toString(), small.copy(maxBatchBytes = 1))
        assertFails { constrained.prepareBatch() }
        constrained.close()
        val reopened = DiagnosticStore(root.toString(), small)
        assertEquals(batch.id, reopened.prepareBatch().id)
        assertEquals(2, reopened.pendingReportCount())
        assertFails { reopened.exportBatch(batch, Path(base, "exports").toString()) }
        val fresh = reopened.prepareBatch()
        val export = reopened.exportBatch(fresh, Path(base, "exports").toString())
        reopened.deleteExported(export)
        assertEquals(1, reopened.pendingReportCount())
        reopened.close()
        val tiny = DiagnosticStore(root.toString(), small.copy(maxBatchBytes = 1))
        assertFails { tiny.prepareBatch() }
        assertEquals(1, tiny.pendingReportCount())
        tiny.close()
    }

    @Test fun streamingFilesRestartAndExplicitAcknowledgement() = inDirectory { root ->
        var store = DiagnosticStore(root.toString(), small)
        store.append("frozen log")
        store.recordReport(ReportKind.CRASH, "frozen crash")
        val original = store.prepareBatch()
        val reader = store.openFile(original, original.files.first().id)
        assertFails { reader.read(65537) }
        reader.close()
        assertFails { reader.read() }
        assertFails { store.openFile(original, "../log_0.txt") }
        store.close()
        store = DiagnosticStore(root.toString(), small)
        val restored = store.prepareBatch()
        assertEquals(original.id, restored.id)
        assertEquals(original.files, restored.files)
        assertFails { store.acknowledgeBatch(original) }
        restored.files.forEach { file ->
            val stream = store.openFile(restored, file.id)
            val bytes = mutableListOf<Byte>()
            try {
                while (true) {
                    val chunk = stream.read(3)
                    if (chunk.isEmpty()) break
                    bytes.addAll(chunk.toList())
                }
            } finally { stream.close() }
            assertEquals(file.size, bytes.size.toLong())
            assertEquals(read(Path(root, "pending", file.name)), bytes.toByteArray().decodeToString())
        }
        // 模拟部分上传成功后重试：未确认之前完整批次仍在。
        assertEquals(restored.files, store.prepareBatch().files)
        store.append("later log")
        store.acknowledgeBatch(restored)
        assertEquals("later log\n", read(Path(root, "log_0.txt")))
        assertFails { store.acknowledgeBatch(restored) }
        store.close()
    }

    @Test fun reportNotificationFollowsDiskSuccessAndAllowsReentry() = inDirectory { root ->
        val store = DiagnosticStore(root.toString(), small.copy(maxReports = 1))
        val notified = mutableListOf<ReportKind>()
        val recorder = ReportRecorder(store) { kind ->
            assertEquals(1, store.pendingReportCount()) // 回调不能持有 store 锁。
            notified += kind
        }
        recorder.record(ReportKind.CRASH, "stored")
        recorder.record(ReportKind.HANG, "capacity full")
        recorder.record(ReportKind.SYSTEM, "") // 写入失败不通知。
        assertEquals(listOf(ReportKind.CRASH), notified)
        recorder.close()
        store.acknowledgeBatch(store.prepareBatch())
        recorder.record(ReportKind.HANG, "late")
        assertEquals(0, store.pendingReportCount())
        lateinit var reentrant: ReportRecorder
        reentrant = ReportRecorder(store) { reentrant.close(); error("宿主通知异常") }
        reentrant.record(ReportKind.SYSTEM, "stored before callback closes")
        reentrant.record(ReportKind.CRASH, "after close")
        assertEquals(1, store.pendingReportCount())
        store.close()
        ReportRecorder(store) { error("closed store must not notify") }.record(ReportKind.CRASH, "write fails")
    }

    @Test fun pathAndConfigValidation() {
        assertFailsWith<IllegalArgumentException> { DiagnosticStore("../other") }
        assertFailsWith<IllegalArgumentException> { DiagnosticStore("/tmp/../other") }
        assertFailsWith<IllegalArgumentException> { DiagnosticLimits(maxLogBytes = 0) }
        assertFailsWith<IllegalArgumentException> { DiagnosticFiles.openSnapshot(DiagnosticSnapshotFile("/tmp/file", "file", -1)) }
    }

    @Test fun activeSnapshotReadsOnlyCapturedLengthAndRejectsShortenedSource() = inDirectory { root ->
        val store = DiagnosticStore(root.toString(), small)
        try {
            store.append("captured")
            val snapshot = store.currentFiles().single()
            store.append("later")
            val reader = store.openSnapshot(snapshot)
            try {
                assertEquals("captured\n", reader.read().decodeToString())
                assertTrue(reader.read().isEmpty())
            } finally { reader.close() }
            fs.sink(Path(snapshot.path)).buffered().use { it.writeString("short") }
            assertFails { store.openSnapshot(snapshot) }
            assertFails { store.readTail(snapshot, 4) }
            assertEquals("short", read(Path(snapshot.path)))
        } finally { store.close() }
    }

    @Test fun frozenReaderDetectsGrowthWithoutAcknowledgingOrDeletingEvidence() = inDirectory { root ->
        val store = DiagnosticStore(root.toString(), small)
        try {
            store.append("frozen")
            val batch = store.prepareBatch()
            val file = batch.files.single()
            val reader = store.openFile(batch, file.id)
            try {
                fs.sink(Path(root, "pending", file.name), append = true).buffered().use { it.writeString("tampered") }
                assertFails { reader.read() }
                assertFails { store.acknowledgeBatch(batch) }
                assertEquals("frozen\ntampered", read(Path(root, "pending", file.name)))
            } finally { reader.close() }
        } finally { store.close() }
    }

    private fun read(path: Path) = fs.source(path).buffered().use { it.readString() }
    private fun inDirectory(block: (Path) -> Unit) {
        val root = Path(SystemTemporaryDirectory, "gycrosskit-test-${Random.nextLong().toULong()}")
        fs.createDirectories(root)
        try { block(root) } finally { remove(root) }
    }
    private fun remove(path: Path) {
        if (fs.metadataOrNull(path)?.isDirectory == true) fs.list(path).forEach { remove(it) }
        fs.delete(path)
    }
}
