import DiagnosticsConsumer

// 只验证已发布 KLIB 经 Framework 导出的 Swift 错误契约，不启动系统采集器。
@available(iOS 14.0, *)
func makeVerifiedRecorder(store: Diagnostics_coreDiagnosticStore) -> GYMetricKitRecorder {
    GYMetricKitRecorder { text in
        do { _ = try store.recordReport(kind: .system, text: text) }
        catch { /* 宿主决定如何展示失败；该回调不确认或删除原批次。 */ }
    }
}

func makeVerifiedStore() throws -> Diagnostics_coreDiagnosticStore {
    try IosConsumerKt.iosStore()
}
