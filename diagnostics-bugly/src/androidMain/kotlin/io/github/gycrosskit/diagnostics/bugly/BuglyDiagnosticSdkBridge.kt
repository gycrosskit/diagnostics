package io.github.gycrosskit.diagnostics.bugly

import android.content.Context
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkBridge
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkConfiguration
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkProvider
import com.tencent.bugly.library.Bugly
import com.tencent.bugly.library.BuglyBuilder
import com.tencent.tddiag.TDDiag
import com.tencent.tddiag.TDDiagConfig
import com.tencent.tddiag.logger.TDLog
import com.tencent.tddiag.logger.TDLogConfig
import com.tencent.tddiag.protocol.LogLevel
import com.tencent.tddiag.protocol.LoggerAdapter
import java.io.File

/** 统一接入 Bugly 崩溃与远程日志；日志只接收通用出口过滤后的副本。 */
class BuglyDiagnosticSdkBridge(private val context: Context) : DiagnosticSdkBridge {
    private var remoteLogs = false

    override suspend fun initialize(
        configuration: DiagnosticSdkConfiguration,
        deviceId: String,
        versionName: String,
        buildNumber: String,
    ) {
        require(configuration.provider == DiagnosticSdkProvider.BUGLY) { "Bugly 适配器需要 BUGLY 配置" }
        if (configuration.performance) throw UnsupportedOperationException("Bugly 性能采集尚未接入")
        if (!configuration.crashes && !configuration.remoteLogs) return
        require(configuration.appId.isNotBlank()) { "Bugly 缺少 appId" }
        require(configuration.appKey.isNotBlank()) { "Bugly 缺少 appKey" }
        if (configuration.remoteLogs) {
            // 日志初始化可能抛错；先完成它，避免失败后留下已安装的崩溃 handler。
            TDLog.initialize(context.applicationContext, remoteLogConfiguration(context))
            val logAdapter = checkNotNull(TDLog.getLogImpl()) { "Bugly 远程日志缓存初始化失败" }
            val config = TDDiagConfig.Builder()
                .setAppId(configuration.appId)
                .setAppKey(configuration.appKey)
                .setEnvironment(TDDiagConfig.ENV_PRO)
                .setAppVersion(versionName)
                .setLoggerAdapter(object : LoggerAdapter by logAdapter {
                    // 远程出口只接收过滤后的业务日志，SDK 自身日志可能带初始化参数。
                    override fun printDiagnoseLog(tag: String, msg: String, tr: Throwable?) = Unit
                })
                .build()
            TDDiag.initialize(context.applicationContext, config)
            TDDiag.setUserId(deviceId)
        }
        if (configuration.crashes) {
            val builder = crashOnlyBuglyBuilder(configuration, deviceId, versionName, buildNumber)
            check(Bugly.init(context.applicationContext, builder)) { "Bugly 初始化失败" }
        }
        remoteLogs = configuration.remoteLogs
    }

    override fun writeLog(level: String, tag: String, message: String) {
        if (!remoteLogs) return
        when (level.uppercase()) {
            "V", "VERBOSE" -> TDLog.v(tag, message)
            "D", "DEBUG" -> TDLog.d(tag, message)
            "W", "WARN" -> TDLog.w(tag, message)
            "E", "ERROR" -> TDLog.e(tag, message)
            else -> TDLog.i(tag, message)
        }
    }
}

internal fun remoteLogConfiguration(context: Context): TDLogConfig = TDLogConfig.Builder(context)
    .setLogPath(File(context.noBackupFilesDir, "diagnostic-sdk/bugly").absolutePath)
    .setConsoleLog(false)
    .setLogLevel(LogLevel.VERBOSE)
    .setMaxFileSize(5L * 1024 * 1024)
    .setMaxAliveDay(1)
    // 厂商文档限定总量最小值 200 MiB，不能把更小配置宣称为有效容量限制。
    .setMaxAliveFileSize(200L * 1024 * 1024)
    .build()

internal fun crashOnlyBuglyBuilder(
    configuration: DiagnosticSdkConfiguration,
    deviceId: String,
    versionName: String,
    buildNumber: String,
): BuglyBuilder = BuglyBuilder(configuration.appId, configuration.appKey).apply {
    setServerHostType(BuglyBuilder.ServerHostTypeBuglyPro)
    uniqueId = deviceId
    appVersion = versionName
    this.buildNumber = buildNumber
    appChannel = configuration.channel
    initAppState = BuglyBuilder.APP_STATE_FOREGROUND
    debugMode = false
    // 与固定 4.4.8.2 的默认 monitor 清单一致；升级 SDK 时须重新核对，不能留到 init 后关闭。
    BUGLY_PERFORMANCE_MONITORS.forEach(::removeMonitor)
}

internal val BUGLY_PERFORMANCE_MONITORS = listOf(
    "looper_metric", "looper_stack", "launch_metric", "memory_quantile", "sub_memory_quantile",
    "activity_leak", "big_bitmap", "net_quality", "java_memory_ceiling_hprof", "page_launch",
    "custom_event", "sse",
)
