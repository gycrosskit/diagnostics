package io.github.gycrosskit.diagnostics.sdk

enum class DiagnosticSdkProvider { NONE, BUGLY, UMENG }

/** 客户端接入参数，不存放控制台 Token、符号上传密钥或远程控制凭据。 */
data class DiagnosticSdkConfiguration(
    val provider: DiagnosticSdkProvider = DiagnosticSdkProvider.NONE,
    val appId: String = "",
    val appKey: String = "",
    val channel: String = "",
    val remoteLogs: Boolean = true,
    val brandId: String = "",
    val environment: String = "",
    val crashes: Boolean = true,
    val performance: Boolean = false,
) {
    fun matches(brandId: String, environment: String): Boolean =
        provider != DiagnosticSdkProvider.NONE && appKey.isNotBlank() &&
            (provider != DiagnosticSdkProvider.BUGLY || appId.isNotBlank()) &&
            this.brandId.isNotBlank() && this.brandId == brandId &&
            this.environment in setOf("debug", "qa", "release") && this.environment == environment &&
            (crashes || remoteLogs || performance)

    override fun toString(): String = "DiagnosticSdkConfiguration(provider=$provider, crashes=$crashes, remoteLogs=$remoteLogs, performance=$performance)"
}

/** 只有初始化成功后才允许转发日志；原生实现负责供应商能力差异与崩溃处理器交接。 */
interface DiagnosticSdkBridge {
    suspend fun initialize(
        configuration: DiagnosticSdkConfiguration,
        deviceId: String,
        versionName: String,
        buildNumber: String,
    )

    fun writeLog(level: String, tag: String, message: String)
}
