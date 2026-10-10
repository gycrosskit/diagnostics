package consumer

import io.github.gycrosskit.diagnostics.DiagnosticStore
import io.github.gycrosskit.diagnostics.DiagnosticBatch
import io.github.gycrosskit.diagnostics.ktor.openFileChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.coroutineScope
import kotlinx.io.readByteArray

suspend fun DiagnosticStore.readPublishedFile(batch: DiagnosticBatch, fileId: String): ByteArray = coroutineScope {
    val stream = openFileChannel(batch, fileId, this)
    val bytes = stream.channel.readRemaining().readByteArray()
    stream.awaitComplete()
    check(stream.isClosed && stream.isComplete)
    bytes
}
