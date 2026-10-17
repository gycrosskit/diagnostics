public let BUGLY_MODULE_CRASH = "crash"
public enum BuglyServerHostType { case buglyPro }
public final class BuglyConfig {
    public let appId: String, appKey: String
    public var clientTag = "", appVersion = "", channel = ""
    public var serverHostType: BuglyServerHostType = .buglyPro
    public init(appId: String, appKey: String) { self.appId = appId; self.appKey = appKey }
}
public enum Bugly {
    public static var starts = 0
    public static var config: BuglyConfig?
    public static var modules: [String] = []
    public static func start(_ modules: [String], config: BuglyConfig) {
        starts += 1; self.config = config; self.modules = modules
    }
    // 故意不回调，防止宿主重新依赖供应商没有失败语义的异步完成事件。
    public static func start(_ modules: [String], config: BuglyConfig, completeHandler: (() -> Void)?) {}
}
