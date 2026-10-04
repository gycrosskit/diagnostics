package io.github.gycrosskit.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnrReportStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `system history keeps stable name and parses summary and detail`() {
        val store = AnrReportStore(temporaryFolder.newFolder())
        val report = AnrReport(
            source = AnrReportSource.SYSTEM,
            timestampMillis = 123L,
            pid = 42,
            processName = "host.app",
            foregroundActivity = "HostActivity",
            blockedDurationMillis = 5000L,
            description = "line one\nline two",
            suspectedReason = "reason",
            mainThreadStack = "main stack",
            allThreadStacks = "workers",
            systemTrace = "system trace",
        )
        val target = requireNotNull(store.record(report))
        val firstContent = target.readText()
        assertEquals("anr_system_123_42.txt", target.name)
        assertEquals(target, store.record(report.copy(description = "changed")))
        assertEquals(firstContent, target.readText())
        assertEquals(1, store.pendingReportFiles().size)
        val summary = store.summaries().single()
        assertEquals("line one line two", summary.description)
        assertEquals(AnrReportSource.SYSTEM, summary.source)
        assertEquals(123L, summary.timestampMillis)
        val stored = requireNotNull(store.report(summary.id))
        assertEquals("main stack", stored.report.mainThreadStack)
        assertEquals("workers", stored.report.allThreadStacks)
        assertEquals("system trace", stored.report.systemTrace)
        assertEquals(firstContent, stored.rawText)
        assertNull(store.report("../${target.name}"))
        assertNull(store.report("${target.name}.tmp"))
    }

    @Test
    fun `ANR retention keeps ten reports without touching unrelated files`() {
        val directory = temporaryFolder.newFolder()
        val unrelated = java.io.File(directory, "host.txt").apply { writeText("keep") }
        val store = AnrReportStore(directory)
        repeat(12) { index ->
            requireNotNull(store.record(AnrReport(AnrReportSource.WATCHDOG, index.toLong())))
                .setLastModified(1000L + index)
        }
        assertEquals(10, store.pendingReportFiles().size)
        assertTrue(unrelated.exists())
    }

    @Test
    fun `oversized report is streamed within the file limit`() {
        val directory = temporaryFolder.newFolder()
        val store = AnrReportStore(directory)
        val oversizedTrace = "线程阻塞\n".repeat(AnrReportStore.MAX_REPORT_BYTES / 6)

        val target = store.record(
            AnrReport(
                source = AnrReportSource.WATCHDOG,
                timestampMillis = 123L,
                mainThreadStack = oversizedTrace,
                allThreadStacks = oversizedTrace,
            ),
        )

        assertNotNull(target)
        val content = target?.readText().orEmpty()
        assertTrue(
            (target?.length() ?: Long.MAX_VALUE) <= AnrReportStore.MAX_REPORT_BYTES.toLong(),
        )
        assertTrue(content.contains("ANR REPORT TRUNCATED"))
        assertFalse(directory.walk().any { it.name.endsWith(".tmp") })
    }
}
