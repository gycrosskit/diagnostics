package io.github.gycrosskit.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAnrMonitorTest {
    @Test
    fun `main stack keeps at most configured frames and marks truncation`() {
        val result = formatThreadStack(
            threadName = "main",
            threadState = "RUNNABLE",
            stack = stack(frameCount = 180),
            maxFrames = 100,
            maxCharacters = 48 * 1024,
        )

        assertEquals(100, result.lineSequence().count { it.startsWith("    at ") })
        assertTrue(result.contains("stack frames truncated"))
        assertTrue(result.length <= 48 * 1024)
    }

    @Test
    fun `thread dump stays bounded and reports omitted threads`() {
        val result = formatThreadDump(
            snapshots = List(30) { index ->
                ThreadStackSnapshot(
                    name = "worker-$index",
                    state = "WAITING",
                    stack = stack(frameCount = 120),
                )
            },
            omittedThreads = 4,
            maxFramesPerThread = 80,
            maxCharactersPerThread = 8 * 1024,
            maxCharacters = 16 * 1024,
        )

        assertTrue(result.contains("thread dump truncated"))
        assertTrue(result.length <= 16 * 1024)
    }

    private fun stack(frameCount: Int): Array<StackTraceElement> = Array(frameCount) { index ->
        StackTraceElement("Example$index", "run", "Example.kt", index + 1)
    }
}
