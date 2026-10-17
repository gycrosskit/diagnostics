package io.github.gycrosskit.diagnostics.sdk

enum class DiagnosticSdkProvider { NONE, BUGLY, UMENG }
data class DiagnosticSdkConfiguration(val provider: DiagnosticSdkProvider = DiagnosticSdkProvider.NONE,
    val appId: String = "", val appKey: String = "", val channel: String = "", val remoteLogs: Boolean = true,
    val crashes: Boolean = true, val performance: Boolean = false,
    val brandId: String = "", val environment: String = "")
interface DiagnosticSdkBridge {
    suspend fun initialize(configuration: DiagnosticSdkConfiguration, deviceId: String, versionName: String, buildNumber: String)
    fun writeLog(level: String, tag: String, message: String)
}
