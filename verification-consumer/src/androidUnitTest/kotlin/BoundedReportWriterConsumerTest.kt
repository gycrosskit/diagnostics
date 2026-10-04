package consumer

import io.github.gycrosskit.diagnostics.BoundedReportWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedReportWriterConsumerTest {
    @Test
    fun hostCrashFormatUsesPublicWriterWithItsOwnMarkerAndByteBudget() {
        val writer = StringWriter()
        val maximumBytes = 512 * 1024
        val marker = "\n[CRASH REPORT TRUNCATED]\n"
        val output = BoundedReportWriter(writer, maximumBytes, marker)
        output.appendLine("timestamp=123")
        output.appendLine("exception=java.lang.IllegalStateException")
        output.append("message=")
        output.appendLine("😀汉字".repeat(200_000))
        if (!output.isFull) output.appendLine("\tat Host.run(Host.kt:1)")
        output.finish()

        val text = writer.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= maximumBytes)
        assertTrue(text.startsWith("timestamp=123\n"))
        assertTrue(text.endsWith(marker))
        assertEquals(text, bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun completeCrashTextNeedsNoTruncationMarker() {
        val writer = StringWriter()
        val output = BoundedReportWriter(writer, 1024, "\n[CRASH REPORT TRUNCATED]\n")
        output.appendLine("message=boom")
        output.appendSection("CAUSE", "java.lang.IllegalStateException: boom")
        assertFalse(output.isFull)
        output.finish()
        assertFalse(writer.toString().contains("TRUNCATED"))
        assertTrue(writer.toString().contains("===== CAUSE ====="))
    }
}
