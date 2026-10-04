import Foundation

/// 新旧采集 API 共用一个进程所有者，避免同一 payload 被两套 Subscriber 重复保存。
enum MetricKitOwnership {
    private static let lock = NSLock()
    private static var owner: NSObject?

    static func claim(_ candidate: NSObject) {
        lock.lock()
        defer { lock.unlock() }
        precondition(owner == nil || owner === candidate, "进程已有 GY CrossKit MetricKit 采集器")
        owner = candidate
    }

    static func release(_ candidate: NSObject) {
        lock.lock()
        defer { lock.unlock() }
        if owner === candidate { owner = nil }
    }

    static func collector() -> GYDiagnosticCollector? {
        lock.lock()
        defer { lock.unlock() }
        return owner as? GYDiagnosticCollector
    }
}
