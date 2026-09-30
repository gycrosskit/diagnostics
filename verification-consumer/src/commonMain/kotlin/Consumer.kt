package consumer
import io.github.gycrosskit.diagnostics.*
@Throws(Exception::class)
fun exportDiagnostics(root: String, target: String): String {
    val store = DiagnosticStore(root)
    store.append("consumer local log")
    val reportAccepted = store.recordReport(ReportKind.SYSTEM, "host-provided diagnostic")
    check(reportAccepted)
    val batch = store.prepareBatch()
    val receipt = store.exportBatch(batch, target)
    check(receipt.path.endsWith(".tar"))
    store.deleteExported(receipt)
    store.close()
    return receipt.path
}


@Throws(Exception::class)
fun uploadFiles(root: String, uploadChunk: (String, ByteArray) -> Unit) {
    val store = DiagnosticStore(root)
    try {
        val batch = store.prepareBatch()
        batch.files.forEach { file ->
            val reader = store.openFile(batch, file.id)
            try {
                while (true) {
                    val bytes = reader.read()
                    if (bytes.isEmpty()) break
                    uploadChunk(file.id, bytes)
                }
            } finally { reader.close() }
        }
        store.acknowledgeBatch(batch)
    } finally { store.close() }
}
