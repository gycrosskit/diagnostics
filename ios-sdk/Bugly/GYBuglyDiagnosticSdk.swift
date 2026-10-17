import Foundation
import GYDiagnosticsSdk
import BuglyPro
import TDOS_Diagnose

public final class GYBuglyDiagnosticSdk: GYDiagnosticSdkAdapter {
    private var initialized = false
    private var remoteLogs = false
    private var logDataSource: VendorLogDataSource?
    private var vendorLogger: TDOSLogger?

    public init() {}

    public func initialize(configuration: GYDiagnosticSdkConfiguration, deviceId: String,
                           versionName: String, buildNumber: String, beforeStart: () -> Void) throws {
        guard !initialized else { return }
        guard !configuration.performance else { throw GYDiagnosticSdkError.unsupported("iOS Bugly performance") }
        guard configuration.crashes || configuration.remoteLogs,
              !configuration.appId.isEmpty, !configuration.appKey.isEmpty else { throw GYDiagnosticSdkError.invalidConfiguration }
        if configuration.remoteLogs && !startBuglyLogs(configuration: configuration, deviceId: deviceId, versionName: versionName) {
            throw GYDiagnosticSdkError.startupFailed
        }
        if configuration.crashes {
            let config = BuglyConfig(appId: configuration.appId, appKey: configuration.appKey)
            config.serverHostType = .buglyPro
            config.clientTag = deviceId
            config.appVersion = versionName
            config.channel = configuration.channel
            beforeStart()
            Bugly.start([BUGLY_MODULE_CRASH], config: config)
        }
        initialized = true
        remoteLogs = configuration.remoteLogs
    }

    public func writeLog(level: String, tag: String, message: String) {
        guard initialized, remoteLogs else { return }
        let severity: RAFTLogLevel
        switch level {
        case "ERROR", "E": severity = .error
        case "WARN", "W": severity = .warn
        case "DEBUG", "D": severity = .debug
        default: severity = .info
        }
        vendorLogger?.log(severity, tag: tag, file: "", func: "", line: 0, msg: message)
    }

    private func startBuglyLogs(configuration: GYDiagnosticSdkConfiguration, deviceId: String, versionName: String) -> Bool {
        let loggerConfig = TDOSLoggerConfig.default()
        let logger = TDOSLogger(config: loggerConfig)
        logger.setLogLevel(.info)
        logger.setMaxLogAliveTime(10 * 24 * 60 * 60)
        logger.setMaxTotalLogFileSize(50 * 1024 * 1024)
        logger.setConsoleLogEnabled(false)
        let dataSource = VendorLogDataSource(deviceId: deviceId, versionName: versionName)
        let depends = TDIAGDepends(logImp: logger, kvFactoryImp: VendorLogStorageFactory(),
                                  andFilePackerImp: DiagnosticLogFilePacker(logDirectory: loggerConfig.logPath))
        let config = TDLogSDKConfig(appId: configuration.appId, appKey: configuration.appKey,
                                    dataSource: dataSource, depends: depends)
        config.serverHostType = .buglyPro
        logDataSource = dataSource
        vendorLogger = logger
        // 由 SDK 在启动/前台检查远程捞取命令；不调用主动 uploadFiles。
        return TDLogSDK.sharedInstance().start(with: config)
    }
}
