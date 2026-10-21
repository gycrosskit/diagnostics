public enum UMConfigure {
    public static var starts = 0
    public static var analyticsEnabled = true, logEnabled = true
    public static var asaEnabled = true
    public static var appKey = "", channel = ""
    public static func setLogEnabled(_ enabled: Bool) { logEnabled = enabled }
    public static func setAnalyticsEnabled(_ enabled: Bool) { analyticsEnabled = enabled }
    public static func setASAEnabled(_ enabled: Bool) { asaEnabled = enabled }
    public static func initWithAppkey(_ appkey: String, channel: String) {
        precondition(!asaEnabled, "ASA collection must be disabled before initialization")
        starts += 1; appKey = appkey; self.channel = channel
    }
}
