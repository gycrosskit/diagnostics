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


@available(iOS 14.0, *)
func makeNotifyingRecorder(store: Diagnostics_coreDiagnosticStore) -> GYMetricKitRecorder {
    GYMetricKitRecorder(record: { text in
        do { return try store.recordReport(kind: .system, text: text) }
        catch { return false }
    }, onReportStored: { /* 宿主调度上传；没有原始文本。 */ })
}

func readFrozenFile(store: Diagnostics_coreDiagnosticStore) throws {
    let batch = try store.prepareBatch()
    for file in batch.files {
        let reader = try store.openFile(batch: batch, fileId: file.id)
        do {
            while try reader.read(maxBytes: 16384).size > 0 {}
            try reader.close()
        } catch {
            try? reader.close()
            throw error
        }
    }
    try store.acknowledgeBatch(batch: batch)
}
