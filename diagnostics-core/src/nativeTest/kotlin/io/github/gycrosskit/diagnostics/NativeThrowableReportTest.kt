package io.github.gycrosskit.diagnostics

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeThrowableReportTest {
    @Test
    fun messageUsesOneUtf8BudgetWithoutSplittingCharacters() {
        val report = boundedNativeThrowableReport(IllegalStateException("中😀".repeat(10_000)), 256)
        assertTrue(report.encodeToByteArray().size <= 256)
        assertContains(report, "[THROWABLE REPORT TRUNCATED]")
        assertFalse(report.contains('\uFFFD'))
        assertEquals(report, report.encodeToByteArray().decodeToString())
    }

    @Test
    fun causeAndSuppressedAreRetainedWithinTheBudget() {
        val error = IllegalStateException("outer", IllegalArgumentException("cause"))
        error.addSuppressed(IllegalStateException("suppressed"))
        val report = boundedNativeThrowableReport(error)
        assertContains(report, "IllegalStateException: outer")
        assertContains(report, "Suppressed: IllegalStateException: suppressed")
        assertContains(report, "Caused by: IllegalArgumentException: cause")
    }

    @Test
    fun cyclicAndDeepCausesTerminateWithAMarker() {
        val cycle = boundedNativeThrowableReport(CyclicThrowable())
        assertEquals(1, Regex("CyclicThrowable: cycle").findAll(cycle).count())
        assertContains(cycle, "[THROWABLE REPORT TRUNCATED]")
        var deep: Throwable = IllegalStateException("leaf")
        repeat(30) { deep = IllegalStateException("layer-$it", deep) }
        val report = boundedNativeThrowableReport(deep)
        assertEquals(15, Regex("Caused by:").findAll(report).count())
        assertContains(report, "[THROWABLE REPORT TRUNCATED]")
    }

    @Test
    fun longStacksAndTinyBudgetsStayBounded() {
        val report = boundedNativeThrowableReport(recursiveError(150))
        assertTrue(Regex("    at ").findAll(report).count() <= 100)
        assertContains(report, "[THROWABLE REPORT TRUNCATED]")
        assertFailsWith<IllegalArgumentException> { boundedNativeThrowableReport(Throwable(), 1) }
        assertFailsWith<IllegalArgumentException> { boundedNativeThrowableReport(Throwable(), Int.MIN_VALUE) }
    }

    private fun recursiveError(depth: Int): Throwable = if (depth == 0) IllegalStateException("frames") else {
        val error = recursiveError(depth - 1)
        check(depth > 0)
        error
    }

    private class CyclicThrowable : Throwable("cycle") {
        override val cause: Throwable get() = this
    }
}
