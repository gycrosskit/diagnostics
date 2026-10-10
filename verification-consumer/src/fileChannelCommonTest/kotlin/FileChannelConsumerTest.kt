package consumer

import io.github.gycrosskit.diagnostics.DiagnosticStore
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem as fs
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class FileChannelConsumerTest {
    @Test fun remoteFileChannelReadsAndClosesWithoutDeletingBatch() = runBlocking {
        val root = Path(SystemTemporaryDirectory, "published-channel-${Random.nextLong().toULong()}")
        val store = DiagnosticStore(root.toString())
        try {
            store.append("published rc.11")
            val batch = store.prepareBatch()
            assertEquals("published rc.11\n", store.readPublishedFile(batch, batch.files.single().id).decodeToString())
            assertEquals(batch.id, store.prepareBatch().id)
            store.acknowledgeBatch(batch)
            assertEquals(0, store.prepareBatch().files.size)
        } finally {
            store.close()
            remove(root)
        }
    }

    private fun remove(path: Path) {
        if (fs.metadataOrNull(path)?.isDirectory == true) fs.list(path).forEach { remove(it) }
        fs.delete(path)
    }
}
