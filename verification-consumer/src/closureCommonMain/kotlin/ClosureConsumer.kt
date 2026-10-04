package consumer
import io.github.gycrosskit.diagnostics.*
fun parseIosPayload(json: String) = IosSystemReportParser.parse(json, SystemReportSource.METRICKIT)
fun networkStore() = NetworkLogStore<String>()
@Throws(Exception::class)
fun legacyStore(root: String, oldPending: String) = DiagnosticStore(root,
    legacySources = listOf(LegacyDiagnosticSource(oldPending, listOf("app_", "crash_"), frozen = true)))
@Throws(Exception::class)
fun snapshot(store: DiagnosticStore) = store.currentFiles()
