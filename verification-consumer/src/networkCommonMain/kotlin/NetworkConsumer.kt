package consumer

import io.github.gycrosskit.diagnostics.*
import io.github.gycrosskit.diagnostics.ktor.DiagnosticsKtor
import io.ktor.client.HttpClientConfig

/** 只验证安装 API，不发网络请求；CMP/Kuikly 使用同一份无 UI 依赖的接线。 */
fun HttpClientConfig<*>.networkDiagnostics(store: NetworkLogStore<String>) {
    install(DiagnosticsKtor) {
        capture = NetworkCapture(store, { "qa" }, captureBody = true, redactBody = { redactNetworkJson(it) })
    }
}
