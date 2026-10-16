package io.github.gycrosskit.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlin.test.*

class DiagnosticSnapshotConcurrencyTest {
    @Test fun oldPathSnapshotSilentlyReadsReplacementAfterRotation() = inStore { directory, store ->
        store.append("old")
        val files = store.currentFiles()
        store.append("N".repeat(61))
        val output = File(directory, "old.zip")
        exportDiagnosticZip(output.path, listOf(DiagnosticZipFile("log.txt", files.single())))
        assertEquals("NNNN", zipText(output, "log.txt"))
    }

    @Test fun openedSnapshotSurvivesRotationPreparationAcknowledgementAndNewReports() = inStore { directory, store ->
        store.append("old")
        store.openCurrentSnapshot().use { snapshot ->
            store.append("N".repeat(61))
            store.acknowledgeBatch(store.prepareBatch())
            assertTrue(store.recordReport(ReportKind.CRASH, "new crash"))
            val output = File(directory, "stable.zip")
            exportDiagnosticZip(output.path, listOf(snapshot), listOf(DiagnosticZipFile("log.txt", snapshot.files.single())))
            assertEquals("old\n", zipText(output, "log.txt"))
            assertEquals(1, store.pendingReportCount())
            assertEquals(1, store.currentFiles().size)
        }
    }

    @Test fun slowZipReaderDoesNotBlockNewReportsOrStoreOperations() = inStore { directory, store ->
        store.append("captured")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            store.openCurrentSnapshot().use { original ->
                val file = original.files.single()
                val input = original.reader(file)
                val source = object : RawSource {
                    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        val bytes = input.read(minOf(byteCount, 65536).toInt())
                        if (bytes.isEmpty()) return -1
                        sink.write(bytes)
                        return bytes.size.toLong()
                    }
                    override fun close() = input.close()
                }
                DiagnosticReadSnapshot(listOf(file to DiagnosticFileReader(source.buffered(), file.size, false))).use { gated ->
                    val output = File(directory, "slow.zip")
                    val export = executor.submit<String> {
                        exportDiagnosticZip(output.path, listOf(gated), listOf(DiagnosticZipFile("log.txt", file)))
                    }
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        val write = executor.submit<Boolean> {
                            store.append("N".repeat(61))
                            store.acknowledgeBatch(store.prepareBatch())
                            store.recordReport(ReportKind.CRASH, "during export")
                        }
                        assertTrue(write.get(5, TimeUnit.SECONDS))
                    } finally { release.countDown() }
                    export.get(5, TimeUnit.SECONDS)
                    assertEquals("captured\n", zipText(output, "log.txt"))
                    assertEquals(1, store.pendingReportCount())
                }
            }
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun activeAndPendingNamesRemainDistinctAndLaterAppendIsExcluded() = inStore { directory, store ->
        store.append("pending")
        store.prepareBatch()
        store.append("active")
        store.openCurrentSnapshot().use { snapshot ->
            assertEquals(2, snapshot.files.size)
            assertEquals(1, snapshot.files.map { it.name }.distinct().size)
            store.append("later")
            val output = File(directory, "names.zip")
            val entries = snapshot.files.mapIndexed { index, file -> DiagnosticZipFile("$index/${file.name}", file) }
            exportDiagnosticZip(output.path, listOf(snapshot), entries)
            assertEquals(setOf("active\n", "pending\n"), entries.map { zipText(output, it.archivePath) }.toSet())
            assertEquals(2, store.currentFiles().size)
        }
    }

    @Test fun invalidParametersAndFailedReadsReleaseReadersWithoutDeletingEvidence() = inStore { directory, store ->
        store.append("evidence")
        val before = store.currentFiles()
        lateinit var invalidReader: DiagnosticFileReader
        assertFails {
            store.openCurrentSnapshot().use { snapshot ->
                val file = snapshot.files.single()
                invalidReader = snapshot.reader(file)
                exportDiagnosticZip(File(directory, "invalid.zip").path, listOf(snapshot), listOf(DiagnosticZipFile("../bad", file)))
            }
        }
        assertFails { invalidReader.read() }
        assertEquals(before, store.currentFiles())
        val readFailure = IllegalStateException("read failure")
        var closes = 0
        val file = before.single()
        val broken = DiagnosticFileReader(object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = throw readFailure
            override fun close() { closes++ }
        }.buffered(), file.size, false)
        val output = File(directory, "broken.zip")
        assertSame(readFailure, assertFails {
            DiagnosticReadSnapshot(listOf(file to broken)).use { snapshot ->
                exportDiagnosticZip(output.path, listOf(snapshot), listOf(DiagnosticZipFile("log.txt", file)))
            }
        })
        assertEquals(1, closes)
        assertFalse(output.exists())
        assertFalse(File("${output.path}.tmp").exists())
        assertEquals(before, store.currentFiles())
        assertEquals("evidence\n", File(file.path).readText())
    }

    @Test fun textSnapshotPreservesFormattingAndCanMapLegacyNameWithoutReopening() = inStore { directory, store ->
        store.append("captured")
        store.openCurrentSnapshot().use { snapshot ->
            store.acknowledgeBatch(store.prepareBatch())
            val output = File(directory, "text.txt")
            DiagnosticFiles.exportTextSnapshot(snapshot, output.path, "header") { "host_${it.name}" }
            assertEquals("header\n===== host_log_0.txt (9 bytes) =====\ncaptured\n\n", output.readText())
        }
    }

    @Test fun textSnapshotFiltersEntriesButClosesAllReadersAndProtectsExcludedSources() = inStore { directory, store ->
        store.append("pending")
        store.prepareBatch()
        store.append("active")
        val snapshot = store.openCurrentSnapshot()
        val readers = snapshot.files.map(snapshot::reader)
        val selected = snapshot.files.filter { "/pending/" in it.path }
        val excluded = snapshot.files.single { it !in selected }
        val output = File(directory, "selected.txt")
        snapshot.use {
            assertFailsWith<IllegalArgumentException> {
                DiagnosticFiles.exportTextSnapshot(snapshot, excluded.path, files = selected)
            }
            assertFailsWith<IllegalArgumentException> {
                DiagnosticFiles.exportTextSnapshot(snapshot, output.path, files = selected + selected)
            }
            DiagnosticFiles.exportTextSnapshot(snapshot, output.path, files = selected)
            assertEquals("\n===== log_0.txt (8 bytes) =====\npending\n\n", output.readText())
            assertEquals("active\n", File(excluded.path).readText())
        }
        readers.forEach { reader -> assertFails { reader.read() } }
        assertEquals(2, store.currentFiles().size)
    }

    @Test fun removedExcludedSourceAndItsParentAliasRemainProtectedExportPaths() = inStore { directory, store ->
        store.append("pending")
        store.prepareBatch()
        store.append("active")
        store.openCurrentSnapshot().use { snapshot ->
            val selected = snapshot.files.single { "/pending/" in it.path }
            val excluded = snapshot.files.single { it != selected }
            store.acknowledgeBatch(store.prepareBatch())
            store.acknowledgeBatch(store.prepareBatch())
            val alias = File(directory, "store-alias")
            Files.createSymbolicLink(alias.toPath(), File(excluded.path).parentFile.toPath())
            for (destination in listOf(excluded.path, File(alias, excluded.name).path)) {
                assertFailsWith<IllegalArgumentException> {
                    exportDiagnosticZip(destination, listOf(snapshot), listOf(DiagnosticZipFile("selected.txt", selected)))
                }
                assertFailsWith<IllegalArgumentException> {
                    DiagnosticFiles.exportTextSnapshot(snapshot, destination, files = listOf(selected))
                }
                assertFalse(File(destination).exists())
                assertFalse(File("$destination.tmp").exists())
            }
            assertEquals("pending\n", snapshot.reader(selected).read().decodeToString())
            assertEquals("active\n", snapshot.reader(excluded).read().decodeToString())
        }
    }

    @Test fun openedPendingSnapshotsStillExportAfterTheirParentDirectoryIsRemoved() = inStore { directory, store ->
        store.append("pending evidence")
        val batch = store.prepareBatch()
        store.openCurrentSnapshot().use { textSnapshot ->
            store.openCurrentSnapshot().use { zipSnapshot ->
                val file = textSnapshot.files.single()
                store.acknowledgeBatch(batch)
                assertTrue(File(file.path).parentFile.delete())
                val text = File(directory, "removed-parent.txt")
                DiagnosticFiles.exportTextSnapshot(textSnapshot, text.path)
                assertEquals("\n===== log_0.txt (17 bytes) =====\npending evidence\n\n", text.readText())
                val zip = File(directory, "removed-parent.zip")
                exportDiagnosticZip(zip.path, listOf(zipSnapshot), listOf(DiagnosticZipFile("evidence.txt", file)))
                assertEquals("pending evidence\n", zipText(zip, "evidence.txt"))
            }
        }
    }

    @Test fun removedSnapshotSourceCannotBecomeTheTemporaryExportPath() = inStore { directory, _ ->
        val destination = File(directory, "export")
        val source = File("${destination.path}.tmp").apply { writeText("evidence") }
        val file = DiagnosticFiles.snapshot(source.path)
        DiagnosticReadSnapshot.open(listOf(file)).use { snapshot ->
            assertTrue(source.delete())
            assertFailsWith<IllegalArgumentException> {
                exportDiagnosticZip(destination.path, listOf(snapshot), listOf(DiagnosticZipFile("evidence.txt", file)))
            }
            assertFailsWith<IllegalArgumentException> {
                DiagnosticFiles.exportTextSnapshot(snapshot, destination.path)
            }
            assertFalse(source.exists())
            assertFalse(destination.exists())
            assertEquals("evidence", snapshot.reader(file).read().decodeToString())
        }
    }

    @Test fun legacyZipCanStillExportOneSourceUnderTwoEntryNames() = inStore { directory, store ->
        store.append("evidence")
        val file = store.currentFiles().single()
        val output = File(directory, "duplicates.zip")
        exportDiagnosticZip(output.path, listOf(DiagnosticZipFile("first", file), DiagnosticZipFile("second", file)))
        assertEquals("evidence\n", zipText(output, "first"))
        assertEquals("evidence\n", zipText(output, "second"))
    }

    private fun zipText(zip: File, name: String): String = ZipFile(zip).use { archive ->
        archive.getInputStream(archive.getEntry(name)).bufferedReader().use { it.readText() }
    }
    private fun inStore(block: (File, DiagnosticStore) -> Unit) {
        val directory = Files.createTempDirectory("diagnostics-snapshot").toFile()
        val store = DiagnosticStore(File(directory, "store").path, DiagnosticLimits(maxLogBytes = 64, maxLogFiles = 2))
        try { block(directory, store) } finally { store.close(); directory.deleteRecursively() }
    }
}
