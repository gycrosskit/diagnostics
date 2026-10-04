import Foundation
import MetricKit

/// 保存系统原始 JSON；目录、启用条件、摘要、通知和上传由宿主负责。
@available(iOS 14.0, *)
public final class GYDiagnosticCollector: NSObject, MXMetricManagerSubscriber {
    public let directory: URL
    private let queue = DispatchQueue(label: "io.github.gycrosskit.diagnostics.collector", qos: .utility)
    private let stateLock = NSLock()
    private let fileLock = NSLock()
    private let fileManager = FileManager.default
    private var started = false
    private var previousExceptionHandler: (@convention(c) (NSException) -> Void)?

    /// 宿主提供 Library 下的专属私有目录；不改动已有文件名和 JSON 内容。
    public init(directory: URL) {
        precondition(directory.isFileURL, "诊断目录必须是本地文件 URL")
        self.directory = directory
        super.init()
    }

    /// 在主线程安装；另一个 GYMetricKitRecorder 或 Collector 尚未停止时拒绝重复所有权。
    public func start() {
        precondition(Thread.isMainThread)
        stateLock.lock()
        defer { stateLock.unlock() }
        guard !started else { return }
        MetricKitOwnership.claim(self)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        previousExceptionHandler = NSGetUncaughtExceptionHandler()
        started = true
        MXMetricManager.shared.add(self)
        NSSetUncaughtExceptionHandler(gyRecordUncaughtException)
    }

    /// 在主线程撤回入口并等待已经接受的 MetricKit 写入。先 stop 再关闭宿主诊断存储。
    public func stop() {
        precondition(Thread.isMainThread)
        stateLock.lock()
        guard started else { stateLock.unlock(); return }
        started = false
        stateLock.unlock()
        MXMetricManager.shared.remove(self)
        if let current = NSGetUncaughtExceptionHandler(),
           unsafeBitCast(current, to: UInt.self) == unsafeBitCast(gyRecordUncaughtException as @convention(c) (NSException) -> Void, to: UInt.self) {
            NSSetUncaughtExceptionHandler(previousExceptionHandler)
        }
        queue.sync {}
        MetricKitOwnership.release(self)
    }

    public func didReceive(_ payloads: [MXMetricPayload]) {
        payloads.forEach { persist($0.jsonRepresentation(), prefix: "metrickit_metric") }
    }

    public func didReceive(_ payloads: [MXDiagnosticPayload]) {
        payloads.forEach { persist($0.jsonRepresentation(), prefix: "metrickit_diagnostic") }
    }

    fileprivate func recordUncaughtException(_ exception: NSException) {
        let value: [String: Any] = [
            "timestamp": Date().timeIntervalSince1970,
            "name": exception.name.rawValue,
            "reason": exception.reason ?? "",
            "callStackSymbols": exception.callStackSymbols
        ]
        // 未捕获异常后可能立即退出进程；同步落盘，不等待 MetricKit 队列。
        if let data = try? JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted]) {
            writeAndTrim(data, prefix: "crash")
        }
        previousExceptionHandler?(exception)
    }

    private func persist(_ data: Data, prefix: String) {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard started else { return }
        queue.async { [weak self] in self?.writeAndTrim(data, prefix: prefix) }
    }

    private func writeAndTrim(_ data: Data, prefix: String) {
        fileLock.lock()
        defer { fileLock.unlock() }
        let timestamp = Int(Date().timeIntervalSince1970 * 1_000)
        let target = directory.appendingPathComponent("\(prefix)_\(timestamp)_\(UUID().uuidString).json")
        do {
            try data.write(to: target, options: .atomic)
        } catch { return }
        let files = (try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )) ?? []
        files.filter { $0.lastPathComponent.hasPrefix(prefix + "_") }
            .sorted { lhs, rhs in
                let left = (try? lhs.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
                let right = (try? rhs.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
                return (left ?? .distantPast) > (right ?? .distantPast)
            }
            .dropFirst(10)
            .forEach { try? fileManager.removeItem(at: $0) }
    }
}

private func gyRecordUncaughtException(_ exception: NSException) {
    MetricKitOwnership.collector()?.recordUncaughtException(exception)
}
