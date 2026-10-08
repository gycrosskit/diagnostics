package io.github.gycrosskit.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OhosSystemReportsTest {
    @Test fun subscribedSystemEventsKeepCrashAndHangDistinct() {
        assertEquals(ReportKind.CRASH, ohosSystemReportKind("APP_CRASH"))
        assertEquals(ReportKind.HANG, ohosSystemReportKind("APP_FREEZE"))
        assertNull(ohosSystemReportKind("APP_LAUNCH"))
    }
}
