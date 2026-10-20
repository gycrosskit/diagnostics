import Foundation
import GYDiagnosticsSdk
import UMCommon
#if GY_DIAGNOSTICS_TEST_STUBS
import UMAPMHeadersStub
#endif

public final class GYUmengDiagnosticSdk: GYDiagnosticSdkAdapter {
    private var initialized = false
    private var remoteLogs = false

    public init() {}

    public func initialize(configuration: GYDiagnosticSdkConfiguration, deviceId: String,
                           versionName: String, buildNumber: String, beforeStart: () -> Void) throws {
        guard !initialized else { return }
        guard !configuration.performance else { throw GYDiagnosticSdkError.unsupported("iOS Umeng performance") }
        guard configuration.crashes || configuration.remoteLogs, !configuration.appKey.isEmpty else { throw GYDiagnosticSdkError.invalidConfiguration }
        let config = UMAPMConfig.default()
        // UMAPM 2.0.7 仅提供 crash/block 联合开关，crashes=true 会同时启用二者。
        config.crashAndBlockMonitorEnable = configuration.crashes
        config.launchMonitorEnable = false
        config.memMonitorEnable = false
        config.oomMonitorEnable = false
        config.networkEnable = false
        config.javaScriptBridgeEnable = false
        config.pageMonitorEnable = false
        config.logCollectEnable = configuration.remoteLogs
        config.logCollectUserId = deviceId
        UMCrashConfigure.setAPMConfig(config)
        UMCrashConfigure.setAppVersion(versionName, buildVersion: buildNumber)
        UMConfigure.setLogEnabled(false)
        UMConfigure.setAnalyticsEnabled(false)
        if configuration.crashes { beforeStart() }
        initialized = true
        remoteLogs = configuration.remoteLogs
        UMConfigure.initWithAppkey(configuration.appKey, channel: configuration.channel)
        // 可检索维度须在 SDK 初始化后设置；channel 继续表示分发渠道。
        let data = UMAPMCustomData()
        data.putStringParam(configuration.brandId, forKey: .key1)
        data.putStringParam(configuration.environment, forKey: .key2)
        UMCrashConfigure.update(data)
    }

    public func writeLog(level: String, tag: String, message: String) {
        guard initialized, remoteLogs else { return }
        switch level {
        case "ERROR", "E": UAPMLog.error(tag, msg: message)
        case "WARN", "W": UAPMLog.warn(tag, msg: message)
        case "DEBUG", "D": UAPMLog.debug(tag, msg: message)
        default: UAPMLog.info(tag, msg: message)
        }
    }
}
