package consumer

import io.github.gycrosskit.diagnostics.sdk.*

fun sdkDefaultIsLocalOnly(): Boolean = DiagnosticSdkConfiguration().provider == DiagnosticSdkProvider.NONE
fun createSdkRuntime(config: DiagnosticSdkConfiguration, bridge: () -> DiagnosticSdkBridge) =
    DiagnosticSdkRuntime(config, "brand", "qa", "1.0", "1", bridge)
