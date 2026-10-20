package io.github.gycrosskit.diagnostics.umeng

import android.content.Context
import android.os.Bundle
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkBridge
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkConfiguration
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkProvider
import com.umeng.commonsdk.UMConfigure
import com.umeng.analytics.MobclickAgent
import com.umeng.logsdk.ULogManager
import com.umeng.umcrash.UMCrash
import com.umeng.umcrash.custommapping.UAPMCustomMapping

/** 友盟日志回捞使用厂商有界缓存，宿主私有日志的存储和导出仍由原组件负责。 */
class UmengDiagnosticSdkBridge(private val context: Context) : DiagnosticSdkBridge {
    private var remoteLogs = false

    override suspend fun initialize(
        configuration: DiagnosticSdkConfiguration,
        deviceId: String,
        versionName: String,
        buildNumber: String,
    ) {
        require(configuration.provider == DiagnosticSdkProvider.UMENG) { "友盟适配器需要 UMENG 配置" }
        if (configuration.performance) throw UnsupportedOperationException("友盟性能采集尚未接入")
        if (!configuration.crashes && !configuration.remoteLogs) return
        require(configuration.appKey.isNotBlank()) { "友盟缺少 appKey" }
        UMConfigure.setLogEnabled(false)
        MobclickAgent.setCatchUncaughtExceptions(false)
        MobclickAgent.disable()
        MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.LEGACY_MANUAL)
        UMCrash.setDebug(false)
        UMCrash.setLogCollectSaveSize(16L * 1024)
        // 2.1.0 的 initConfig 忽略 KEY_LOG_DEVICES_ID；必须在 init 前设置，才能阻止日志回退到 UMID。
        ULogManager.setDeviceID(deviceId.take(128))
        UMCrash.initConfig(crashOnlyUmengConfiguration(configuration, deviceId))
        UMCrash.setAppVersion(versionName, configuration.environment, buildNumber)
        UMConfigure.preInit(context.applicationContext, configuration.appKey, configuration.channel)
        UMConfigure.init(context.applicationContext, configuration.appKey, configuration.channel,
            UMConfigure.DEVICE_TYPE_PHONE, null)
        // 可检索维度须在 SDK 初始化后设置；channel 继续表示分发渠道。
        UAPMCustomMapping.putStringParam(UAPMCustomMapping.STRING_PARAM_1, configuration.brandId)
        UAPMCustomMapping.putStringParam(UAPMCustomMapping.STRING_PARAM_2, configuration.environment)
        remoteLogs = configuration.remoteLogs
    }

    override fun writeLog(level: String, tag: String, message: String) {
        if (!remoteLogs || message.isEmpty()) return
        val logTag = tag.ifEmpty { "App" }.take(64).dropLastWhile { it.isHighSurrogate() }
        umengLogChunks(message).forEach { part ->
            when (level.uppercase()) {
                "V", "VERBOSE" -> ULogManager.v(logTag, part)
                "D", "DEBUG" -> ULogManager.d(logTag, part)
                "W", "WARN" -> ULogManager.w(logTag, part)
                "E", "ERROR" -> ULogManager.e(logTag, part)
                else -> ULogManager.i(logTag, part)
            }
        }
    }
}

/** 固定 SDK 在 1024 个 UTF-16 单元处拒收整条日志，分片不得截断 emoji 代理对。 */
internal fun umengLogChunks(message: String): List<String> = buildList {
    var start = 0
    while (start < message.length) {
        var end = minOf(start + 1024, message.length)
        if (end < message.length && message[end - 1].isHighSurrogate() && message[end].isLowSurrogate()) end--
        add(message.substring(start, end))
        start = end
    }
}

internal fun crashOnlyUmengConfiguration(configuration: DiagnosticSdkConfiguration, deviceId: String): Bundle = Bundle().apply {
    putBoolean(UMCrash.KEY_ENABLE_CRASH_JAVA, configuration.crashes)
    putBoolean(UMCrash.KEY_ENABLE_CRASH_NATIVE, configuration.crashes)
    putBoolean(UMCrash.KEY_ENABLE_ANR, configuration.crashes)
    listOf(UMCrash.KEY_ENABLE_CRASH_UNEXP, UMCrash.KEY_ENABLE_PA, UMCrash.KEY_ENABLE_LAUNCH,
        UMCrash.KEY_ENABLE_MEM, UMCrash.KEY_ENABLE_NET, UMCrash.KEY_ENABLE_H5PAGE, UMCrash.KEY_ENABLE_PAGE,
        UMCrash.KEY_ENABLE_POWER, UMCrash.KEY_ENABLE_FLUTTER, UMCrash.KEY_ENABLE_MEMLEAK,
    ).forEach { putBoolean(it, false) }
    putBoolean(UMCrash.KEY_ENABLE_CODE_LOG, configuration.remoteLogs)
    // 日志使用安装 ID，不把业务账号作为供应商识别字段。
    putString(UMCrash.KEY_LOG_USER_ID, deviceId.take(128))
}
