package io.github.gycrosskit.diagnostics

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TarConsumerTest {
    @Test fun systemTarReadsExactContentAndConcurrentWritesStayBounded() {
        val directory = Files.createTempDirectory("diagnostic-consumer").toFile()
        try {
            val root = directory.resolve("private")
            val store = DiagnosticStore(root.absolutePath, DiagnosticLimits(maxLogBytes = 1024, maxLogFiles = 3))
            val workers = Executors.newFixedThreadPool(4)
            repeat(4) { worker -> workers.submit { repeat(100) { store.append("worker=$worker line=$it") } } }
            workers.shutdown()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            val batch = store.prepareBatch()
            assertTrue(batch.files.size <= 3)
            assertTrue(batch.files.all { it.size <= 1024 })
            val export = store.exportBatch(batch, directory.resolve("export").absolutePath)
            val list = command("tar", "-tf", export.path).decodeToString().lineSequence().filter { it.isNotEmpty() }.toList()
            assertEquals(batch.files.map { it.name }, list)
            batch.files.forEach { file ->
                assertContentEquals(root.resolve("pending/${file.name}").readBytes(), command("tar", "-xOf", export.path, file.name))
            }
            store.deleteExported(export)
            assertTrue(root.resolve("pending").listFiles().orEmpty().isEmpty())
            store.close()
        } finally { directory.deleteRecursively() }
    }

    @Test fun failureDuringTarWriteLeavesFrozenFilesAndNoPublicArchive() {
        val directory = Files.createTempDirectory("diagnostic-failure").toFile()
        try {
            val root = directory.resolve("private")
            val store = DiagnosticStore(root.absolutePath)
            store.append("A".repeat(1023))
            val batch = store.prepareBatch()
            val source = root.resolve("pending/${batch.files.single().name}")
            val expected = source.readBytes()
            assertEquals(1024, expected.size)
            val exports = directory.resolve("exports")
            assertTrue(source.setReadable(false, false))
            try { assertFails { store.exportBatch(batch, exports.absolutePath) } }
            finally { assertTrue(source.setReadable(true, true)) }
            assertContentEquals(expected, source.readBytes())
            assertTrue(exports.listFiles().orEmpty().isEmpty())
            val export = store.exportBatch(batch, exports.absolutePath)
            assertContentEquals(expected, command("tar", "-xOf", export.path, batch.files.single().name))
            store.deleteExported(export)
            store.close()
        } finally { directory.deleteRecursively() }
    }

    private fun command(vararg args: String): ByteArray {
        val process = ProcessBuilder(*args).start()
        val output = process.inputStream.readBytes()
        val error = process.errorStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), error)
        return output
    }
}
