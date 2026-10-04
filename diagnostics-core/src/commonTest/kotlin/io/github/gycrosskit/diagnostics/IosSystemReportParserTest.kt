package io.github.gycrosskit.diagnostics
import kotlin.test.*
class IosSystemReportParserTest {
    @Test fun parsesAllFiveArraysAndNeutralFieldsWithBounds() {
        val json = """{"timeStampEnd":"2026-09-30T08:00:00Z","payload":{
          "crashDiagnostics":[{"terminationReason":"watchdog","pid":42,"callStackTree":{"frames":[1]}}],
          "hangDiagnostics":[{"hangDuration":"1.25 sec"}],
          "cpuExceptionDiagnostics":[{"totalCPUTime":4}],
          "diskWriteExceptionDiagnostics":[{"totalWritesCaused":"1000"}],
          "memoryResourceExceptionDiagnostics":[{"processName":"neutral"}]}}"""
        val result = IosSystemReportParser.parse(json, SystemReportSource.METRICKIT, maxDetailCharacters = 24)
        assertEquals(ReportParseStatus.PARSED, result.status)
        assertEquals(listOf(SystemReportKind.WATCHDOG, SystemReportKind.HANG, SystemReportKind.CPU, SystemReportKind.DISK, SystemReportKind.MEMORY), result.reports.map { it.kind })
        assertEquals(42, result.reports.first().processId)
        assertEquals(1250, result.reports[1].durationMillis)
        assertTrue(result.reports.all { it.timestampMillis > 0 && it.rawText.length <= 24 && it.systemTrace.length <= 24 })
        assertTrue(IosSystemReportParser.parse(json, SystemReportSource.METRICKIT, includeDetails = false).reports.all { it.rawText.isEmpty() && it.callStack.isEmpty() })
    }
    @Test fun exceptionAndUnknownOrMalformedFieldsStayControlled() {
        val result = IosSystemReportParser.parse("""{"name":"NSException","reason":"boom","timestamp":1700000000,"callStackSymbols":["frame",{}],"processName":[]} """, SystemReportSource.NS_EXCEPTION)
        val report = result.reports.single()
        assertEquals(1700000000000, report.timestampMillis)
        assertEquals("NSException: boom", report.description)
        assertEquals("", report.processName)
        assertTrue(report.callStack.contains("frame"))
        val malformed = IosSystemReportParser.parse("{bad", SystemReportSource.METRICKIT)
        assertEquals(ReportParseStatus.INVALID_JSON, malformed.status)
        assertEquals(ReportParseStatus.INPUT_TOO_LARGE, IosSystemReportParser.parse("x".repeat(100), SystemReportSource.METRICKIT, maxInputCharacters = 10).status)
        assertTrue(IosSystemReportParser.parse("""{"unknown":1,"hangDiagnostics":[{},"bad"]}""", SystemReportSource.METRICKIT, fallbackTimestampMillis = 17).reports.single().timestampMillis == 17L)
        assertEquals(ReportParseStatus.INVALID_JSON, IosSystemReportParser.parse("[".repeat(1000), SystemReportSource.METRICKIT).status)
    }
    @Test fun sha256MatchesPublishedVectorsAndBlockBoundaries() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", diagnosticFingerprint(ByteArray(0)))
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", diagnosticFingerprint("a".repeat(1000000).encodeToByteArray()))
    }
}
