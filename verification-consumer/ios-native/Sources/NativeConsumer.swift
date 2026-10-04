import Foundation
import GYDiagnosticsNative

/// 真实依赖 Package product，宿主只持有一个采集器，并注入既有 Library 目录。
public final class NativeConsumer {
    private let collector: GYDiagnosticCollector

    public init(libraryDirectory: URL) {
        collector = GYDiagnosticCollector(
            directory: libraryDirectory.appendingPathComponent("SkillCureDiagnostics", isDirectory: true)
        )
    }

    public func start() { collector.start() }
    public func stop() { collector.stop() }
}
