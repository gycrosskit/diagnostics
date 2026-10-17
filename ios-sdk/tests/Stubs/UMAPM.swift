public final class UMAPMConfig {
    public var crashAndBlockMonitorEnable = true, launchMonitorEnable = true, memMonitorEnable = true
    public var oomMonitorEnable = true, networkEnable = true, javaScriptBridgeEnable = true, pageMonitorEnable = true
    public var logCollectEnable = true, logCollectUserId = ""
    public static func `default`() -> UMAPMConfig { UMAPMConfig() }
}
public enum UMCrashConfigure {
    public static var config: UMAPMConfig?
    public static var version = "", build = ""
    public static func setAPMConfig(_ config: UMAPMConfig) { self.config = config }
    public static func setAppVersion(_ version: String, buildVersion: String) { self.version = version; build = buildVersion }
}
public enum UAPMLog {
    public static var levels: [String] = []
    public static func error(_ tag: String, msg: String) { levels.append("error") }
    public static func warn(_ tag: String, msg: String) { levels.append("warn") }
    public static func debug(_ tag: String, msg: String) { levels.append("debug") }
    public static func info(_ tag: String, msg: String) { levels.append("info") }
}
