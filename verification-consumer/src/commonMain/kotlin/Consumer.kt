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
