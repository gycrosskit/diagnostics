import Foundation
import MetricKit

/// iOS 14+；宿主把系统延迟投递的诊断 JSON 接入 DiagnosticStore.recordReport(.system, text)。
/// 不安装 NSException/signal handler；MetricKit 的回调不能保证每次崩溃都即时产生报告。
@available(iOS 14.0, *)
public final class GYMetricKitRecorder: NSObject, MXMetricManagerSubscriber {
    private let queue = DispatchQueue(label: "io.github.gycrosskit.diagnostics.metrickit", qos: .utility)
    private let record: (String) -> Bool
    private let onReportStored: (() -> Void)?
    private let stateLock = NSLock()
    private var started = false

    public init(record: @escaping (String) -> Void) {
        self.record = { text in record(text); return false }
        self.onReportStored = nil
    }

    /// record 只在报告实际落盘后返回 true；通知不携带诊断原文。
    public init(record: @escaping (String) -> Bool, onReportStored: @escaping () -> Void) {
        self.record = record
        self.onReportStored = onReportStored
    }

    /// 在主线程调用；用户授权和是否启用由宿主决定。
    public func start() {
        precondition(Thread.isMainThread)
        stateLock.lock()
        guard !started else { stateLock.unlock(); return }
        MetricKitOwnership.claim(self)
        started = true
        stateLock.unlock()
        MXMetricManager.shared.add(self)
    }

    /// 在主线程调用；返回前等待此前提交的诊断写入，之后可关闭 store。
    public func stop() {
        precondition(Thread.isMainThread)
        stateLock.lock()
        guard started else { stateLock.unlock(); return }
        started = false
        stateLock.unlock()
        MXMetricManager.shared.remove(self)
        queue.sync {}
        MetricKitOwnership.release(self)
    }

    public func didReceive(_ payloads: [MXDiagnosticPayload]) {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard started else { return }
        payloads.forEach { payload in
            let data = payload.jsonRepresentation()
            queue.async { [weak self] in
                guard let self, let text = String(data: data, encoding: .utf8) else { return }
                if self.record(text) { self.onReportStored?() }
            }
        }
    }
}
