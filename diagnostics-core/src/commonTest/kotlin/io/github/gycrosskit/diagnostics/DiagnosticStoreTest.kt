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

    @Test fun pathAndConfigValidation() {
        assertFailsWith<IllegalArgumentException> { DiagnosticStore("../other") }
        assertFailsWith<IllegalArgumentException> { DiagnosticStore("/tmp/../other") }
        assertFailsWith<IllegalArgumentException> { DiagnosticLimits(maxLogBytes = 0) }
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
