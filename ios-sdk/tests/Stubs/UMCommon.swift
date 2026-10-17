public enum UMConfigure {
    public static var starts = 0
    public static var analyticsEnabled = true, logEnabled = true
    public static var appKey = "", channel = ""
    public static func setLogEnabled(_ enabled: Bool) { logEnabled = enabled }
    public static func setAnalyticsEnabled(_ enabled: Bool) { analyticsEnabled = enabled }
    public static func initWithAppkey(_ appkey: String, channel: String) {
        starts += 1; appKey = appkey; self.channel = channel
    }
}
