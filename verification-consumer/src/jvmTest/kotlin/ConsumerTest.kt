package consumer
import java.nio.file.Files
import java.io.File
import kotlin.test.*
class ConsumerTest {
    @Test fun publishedApiProducesReadableArchive() {
        val temp = Files.createTempDirectory("diagnostics-maven-consumer").toFile()
        try {
            val path = exportDiagnostics(File(temp, "private").absolutePath, File(temp, "exports").absolutePath)
            val process = ProcessBuilder("tar", "-tf", path).start()
            val output = process.inputStream.readBytes().decodeToString()
            assertEquals(0, process.waitFor())
            assertTrue(output.contains("log_0.txt")); assertTrue(output.contains("system_"))
        } finally { temp.deleteRecursively() }
    }
}
