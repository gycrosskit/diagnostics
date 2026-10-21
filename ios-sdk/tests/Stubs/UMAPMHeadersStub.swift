import UMCommon

public final class UMAPMConfig {
    public var crashAndBlockMonitorEnable = true, launchMonitorEnable = true, memMonitorEnable = true
    public var oomMonitorEnable = true, networkEnable = true, javaScriptBridgeEnable = true, pageMonitorEnable = true
    public var logCollectEnable = true, logCollectUserId = ""
    public static func `default`() -> UMAPMConfig { UMAPMConfig() }
}
public enum UMCrashConfigure {
    public static var config: UMAPMConfig?
    public static var customData: UMAPMCustomData?
    public static var updates = 0
    public static var version = "", build = ""
    public static func setAPMConfig(_ config: UMAPMConfig) { self.config = config }
    public static func setAppVersion(_ version: String, buildVersion: String) { self.version = version; build = buildVersion }
    public static func update(_ data: UMAPMCustomData) {
        precondition(UMConfigure.starts == 1, "自定义维度必须在 SDK 初始化后设置")
        customData = data; updates += 1
    }
}
public enum UAPMLog {
    public static var levels: [String] = []
    public static func error(_ tag: String, msg: String) { levels.append("error") }
    public static func warn(_ tag: String, msg: String) { levels.append("warn") }
    public static func debug(_ tag: String, msg: String) { levels.append("debug") }
    public static func info(_ tag: String, msg: String) { levels.append("info") }
}
public enum UMAPMCustomStringDataKey: String { case key1 = "s1", key2 = "s2" }
public final class UMAPMCustomData {
    public private(set) var values: [UMAPMCustomStringDataKey: String] = [:]
    public init() {}
    @discardableResult
    public func putStringParam(_ value: String?, forKey key: UMAPMCustomStringDataKey) -> Bool {
        values[key] = value ?? ""
        return true
    }
}
