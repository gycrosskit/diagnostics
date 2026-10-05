package consumer

import io.github.gycrosskit.diagnostics.*
import io.github.gycrosskit.diagnostics.okhttp.NetworkDiagnosticsInterceptor
import okhttp3.OkHttpClient

/** 构造仅内存对象，验证可选 OkHttp 模块公开符号和传递依赖。 */
fun okhttpDiagnostics(store: NetworkLogStore<String>) = OkHttpClient.Builder()
    .addInterceptor(NetworkDiagnosticsInterceptor(NetworkCapture(store, { "qa" }))).build()
