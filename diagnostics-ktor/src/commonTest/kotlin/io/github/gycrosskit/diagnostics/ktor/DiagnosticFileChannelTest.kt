package io.github.gycrosskit.diagnostics.ktor

import io.github.gycrosskit.diagnostics.DiagnosticStore
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem as fs
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.*

class DiagnosticFileChannelTest {
    @Test fun fullReadClosesWithoutAcknowledgingBatch() = runTest {
        withStore { store, _ ->
            store.append("safe log")
            val batch = store.prepareBatch()
            val stream = store.openFileChannel(batch, batch.files.single().id, this)
            assertEquals("safe log\n", stream.channel.readRemaining().readByteArray().decodeToString())
            stream.awaitComplete()
            assertTrue(stream.isClosed && stream.isComplete)
            assertEquals(batch.id, store.prepareBatch().id)
            store.acknowledgeBatch(batch)
            assertTrue(store.prepareBatch().files.isEmpty())
        }
    }

    @Test fun ownerCancelledBeforeDispatchDoesNotOpenReader() = runTest {
        withStore { store, _ ->
            store.append("retained")
            val batch = store.prepareBatch()
            val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val stream = store.openFileChannel(batch, batch.files.single().id, owner)
            owner.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { stream.awaitComplete() }
            assertTrue(stream.isClosed)
            assertFalse(stream.isComplete)
            assertEquals(batch.id, store.prepareBatch().id)
        }
    }

    @Test fun consumerCancelledBeforeReadPreservesBatch() = runTest {
        withStore { store, _ ->
            store.append("retained")
            val batch = store.prepareBatch()
            val stream = store.openFileChannel(batch, batch.files.single().id, this)
            stream.channel.cancel(CancellationException("consumer stopped"))
            runCurrent()
            assertFailsWith<CancellationException> { stream.awaitComplete() }
            assertTrue(stream.isClosed)
            assertFalse(stream.isComplete)
            assertEquals(batch.id, store.prepareBatch().id)
        }
    }

    @Test fun consumerCancelledWhileBackpressuredClosesReader() = runTest {
        withStore { store, _ ->
            store.append("x".repeat(2 * 1024 * 1024))
            val batch = store.prepareBatch()
            val stream = store.openFileChannel(batch, batch.files.single().id, this)
            runCurrent()
            assertFalse(stream.isClosed)
            stream.channel.cancel(CancellationException("consumer stopped"))
            assertFailsWith<CancellationException> { stream.awaitComplete() }
            assertTrue(stream.isClosed)
            assertFalse(stream.isComplete)
            assertEquals(batch.id, store.prepareBatch().id)
        }
    }

    @Test fun shortenedFilePropagatesReadFailureAndPreservesBatch() = runTest {
        withStore { store, root ->
            store.append("original")
            val batch = store.prepareBatch()
            val file = batch.files.single()
            fs.sink(Path(root, "pending", file.name)).buffered().use { it.writeString("short") }
            val stream = store.openFileChannel(batch, file.id, this)
            assertFails { stream.awaitComplete() }
            assertFalse(stream.isComplete)
            assertTrue(fs.exists(Path(root, "pending", file.name)))
        }
    }

    @Test fun readFailureClosesOnceAndKeepsCloseFailureSuppressed() = runTest {
        val readFailure = IllegalStateException("read")
        val closeFailure = IllegalStateException("close")
        var closes = 0
        val failure = assertFailsWith<IllegalStateException> {
            copyDiagnosticFile(ByteChannel(), { throw readFailure }, { closes++; throw closeFailure })
        }
        assertSame(readFailure, failure)
        assertEquals(listOf(closeFailure), failure.suppressedExceptions)
        assertEquals(1, closes)
    }

    @Test fun closeFailureAfterEofIsNotSuccess() = runTest {
        val failure = IllegalStateException("close")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            copyDiagnosticFile(ByteChannel(), { byteArrayOf() }, { throw failure })
        })
        val stream = DiagnosticFileChannel(writer {
            copyDiagnosticFile(channel, { byteArrayOf() }, { throw failure })
        })
        assertFails { stream.awaitComplete() }
        assertTrue(stream.isClosed)
        assertFalse(stream.isComplete)
    }

    @Test fun cancellationAtEofStillClosesAndFails() = runTest {
        var closed = false
        val owner = launch {
            assertFailsWith<CancellationException> {
                copyDiagnosticFile(ByteChannel(), {
                    cancel()
                    byteArrayOf()
                }, { closed = true })
            }
        }
        owner.join()
        assertTrue(closed)
    }

    private suspend fun withStore(block: suspend (DiagnosticStore, Path) -> Unit) {
        val root = Path(SystemTemporaryDirectory, "diagnostic-channel-${Random.nextLong().toULong()}")
        val store = DiagnosticStore(root.toString())
        try { block(store, root) } finally {
            store.close()
            remove(root)
        }
    }

    private fun remove(path: Path) {
        if (fs.metadataOrNull(path)?.isDirectory == true) fs.list(path).forEach { remove(it) }
        fs.delete(path)
    }
}
