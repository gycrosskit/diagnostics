import Foundation

public struct GYDiagnosticSdkConfiguration {
    public let appId: String, appKey: String, channel: String
    public let brandId: String, environment: String
    public let crashes: Bool, performance: Bool, remoteLogs: Bool

    public init(appId: String = "", appKey: String = "", channel: String = "",
                crashes: Bool = true, performance: Bool = false, remoteLogs: Bool = true,
                brandId: String = "", environment: String = "") {
        self.appId = appId; self.appKey = appKey; self.channel = channel
        self.brandId = brandId; self.environment = environment
        self.crashes = crashes; self.performance = performance; self.remoteLogs = remoteLogs
    }
}

public enum GYDiagnosticSdkError: LocalizedError {
    case unsupported(String), invalidConfiguration, startupFailed

    public var errorDescription: String? {
        switch self {
        case .unsupported(let capability): return "Unsupported: " + capability
        case .invalidConfiguration: return "诊断 SDK 配置不完整"
        case .startupFailed: return "诊断 SDK 启动失败"
        }
    }
}

/// 宿主在隐私授权后串行调用；beforeStart 在安装供应商异常处理器前撤回本地采集。
/// initialize 成功仅表示启动调用返回；供应商没有关闭接口，成功后不能重新配置。
public protocol GYDiagnosticSdkAdapter: AnyObject {
    func initialize(configuration: GYDiagnosticSdkConfiguration, deviceId: String,
                    versionName: String, buildNumber: String, beforeStart: () -> Void) throws
    func writeLog(level: String, tag: String, message: String)
}
