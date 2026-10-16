package consumer

import io.github.gycrosskit.diagnostics.DiagnosticFiles
import io.github.gycrosskit.diagnostics.DiagnosticLimits
import io.github.gycrosskit.diagnostics.DiagnosticStore
import io.github.gycrosskit.diagnostics.ReportKind
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem as fs
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotConsumerTest {
    @Test fun publishedSnapshotKeepsCapturedBytesAfterRotationAndAcknowledgement() {
        val root = Path(SystemTemporaryDirectory, "published-snapshot-${Random.nextLong().toULong()}")
        fs.createDirectories(root)
        val store = DiagnosticStore(Path(root, "store").toString(), DiagnosticLimits(maxLogBytes = 64, maxLogFiles = 2))
        try {
            store.append("captured")
            store.openCurrentSnapshot().use { snapshot ->
                store.append("N".repeat(61))
                store.acknowledgeBatch(store.prepareBatch())
                assertTrue(store.recordReport(ReportKind.CRASH, "new report"))
                val output = Path(root, "captured.txt")
                DiagnosticFiles.exportTextSnapshot(snapshot, output.toString())
                assertEquals("\n===== log_0.txt (9 bytes) =====\ncaptured\n\n", fs.source(output).buffered().use { it.readString() })
                assertEquals(1, store.pendingReportCount())
            }
            assertTrue(store.exportPublishedSnapshot(Path(root, "current.txt").toString()) > 0)
        } finally {
            store.close()
            remove(root)
        }
    }

    private fun remove(path: Path) {
        if (fs.metadataOrNull(path)?.isDirectory == true) fs.list(path).forEach(::remove)
        fs.delete(path)
    }
}
