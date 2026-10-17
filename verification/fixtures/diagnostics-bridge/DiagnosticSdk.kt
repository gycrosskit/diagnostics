package io.github.gycrosskit.diagnostics.sdk

object DiagnosticSdkProvider { const val NONE = "none"; const val BUGLY = "bugly"; const val UMENG = "umeng" }
data class DiagnosticSdkConfiguration(val provider: String = DiagnosticSdkProvider.NONE,
    val appId: String = "", val appKey: String = "", val channel: String = "", val remoteLogs: Boolean = true,
    val crashes: Boolean = true, val performance: Boolean = false,
    val brandId: String = "", val environment: String = "")
interface DiagnosticSdkBridge {
    suspend fun initialize(configuration: DiagnosticSdkConfiguration, deviceId: String, versionName: String, buildNumber: String)
    fun writeLog(level: String, tag: String, message: String)
}
