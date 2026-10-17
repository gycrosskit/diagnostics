package io.github.gycrosskit.diagnostics

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlin.test.*

class DiagnosticReadSnapshotLifecycleTest {
    @Test fun closeFailureStillReleasesEveryReaderAndIsIdempotent() {
        val closed = mutableListOf<Int>()
        val first = IllegalStateException("first close")
        val second = IllegalStateException("second close")
        val files = listOf(file("first"), file("second"))
        val snapshot = DiagnosticReadSnapshot(files.mapIndexed { index, file ->
            file to reader { closed += index; throw if (index == 0) first else second }
        })
        val borrowed = snapshot.reader(files.first())
        assertSame(first, assertFails { snapshot.closeWithError() })
        assertEquals(listOf(0, 1), closed)
        assertEquals(listOf(second), first.suppressedExceptions)
        snapshot.close()
        assertEquals(listOf(0, 1), closed)
        assertFails { borrowed.read() }
        assertFails { snapshot.reader(files.first()) }
    }

    @Test fun partialOpenFailureReleasesEarlierReadersAndPreservesPrimaryFailure() {
        val openFailure = IllegalStateException("open")
        val closeFailure = IllegalStateException("close")
        var closed = 0
        assertSame(openFailure, assertFails {
            DiagnosticReadSnapshot.open(listOf(file("first"), file("missing"))) { file ->
                if (file.name == "missing") throw openFailure
                reader { closed++; throw closeFailure }
            }
        })
        assertEquals(1, closed)
        assertEquals(listOf(closeFailure), openFailure.suppressedExceptions)
    }

    private fun file(name: String) = DiagnosticSnapshotFile("/snapshot/$name", name, 1)
    private fun reader(close: () -> Unit) = DiagnosticFileReader(object : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1
        override fun close() = close.invoke()
    }.buffered(), 1, exactLength = false)
}
