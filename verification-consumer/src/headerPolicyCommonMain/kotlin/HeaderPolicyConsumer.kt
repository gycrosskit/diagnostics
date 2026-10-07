package consumer

import io.github.gycrosskit.diagnostics.NetworkCapture
import io.github.gycrosskit.diagnostics.NetworkLogStore

/** 从 Maven 产物编译三端公开回调，不发送请求或输出真实凭据。 */
fun configurableNetworkCapture(store: NetworkLogStore<String>): NetworkCapture<String> =
    NetworkCapture(store, { "qa" }, maxBodyBytes = 32 * 1024, captureBody = true,
        redactBody = { it }, redactHeader = { name, value ->
            if (name.equals("UserSig", ignoreCase = true)) null else value
        })
