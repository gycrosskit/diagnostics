package io.github.gycrosskit.diagnostics

/**
 * 已打开的只读文件集合；长度在打开时捕获，后续追加、rename 或删除不改变本次读取。
 * 不冻结或确认批次。调用方必须 use/finally 关闭；导出函数只借用 reader，不接管生命周期。
 * files 保留私有路径供宿主分类，不应进入公开日志。一个 reader 只消费一次。
 */
class DiagnosticReadSnapshot internal constructor(
    entries: List<Pair<DiagnosticSnapshotFile, DiagnosticFileReader>>,
) : AutoCloseable {
    private val lock = StoreLock()
    internal val entries = entries.toList()
    val files: List<DiagnosticSnapshotFile> = this.entries.map { it.first }
    private var closed = false

    /** 返回已打开的同一 reader，不按路径重新打开；file 必须属于本快照。 */
    @Throws(Exception::class)
    fun reader(file: DiagnosticSnapshotFile): DiagnosticFileReader = lock.locked {
        check(!closed) { "snapshot 已关闭" }
        entries.firstOrNull { it.first == file }?.second
            ?: throw IllegalArgumentException("文件不属于当前快照")
    }

    /** Swift 关闭入口；AutoCloseable.close 的继承契约不允许增加 @Throws。 */
    @Throws(Exception::class)
    fun closeWithError() = close()

    /** 幂等释放所有句柄；一项失败仍继续释放其余项，不确认或删除原件。 */
    override fun close() = lock.locked {
        if (closed) return@locked
        closed = true
        closeSnapshotReaders(entries.map { it.second })
    }

    internal companion object {
        fun open(
            files: List<DiagnosticSnapshotFile>,
            openFile: (DiagnosticSnapshotFile) -> DiagnosticFileReader = DiagnosticFiles::openSnapshot,
        ): DiagnosticReadSnapshot {
            val opened = mutableListOf<Pair<DiagnosticSnapshotFile, DiagnosticFileReader>>()
            try {
                files.forEach { opened += it to openFile(it) }
                return DiagnosticReadSnapshot(opened)
            } catch (failure: Throwable) {
                closeSnapshotReaders(opened.map { it.second }, failure)
                throw failure
            }
        }
    }
}

internal fun closeSnapshotReaders(readers: List<DiagnosticFileReader>, failure: Throwable? = null) {
    var first = failure
    readers.forEach { reader ->
        try { reader.close() } catch (error: Throwable) {
            val previous = first
            if (previous == null) first = error else if (previous !== error) previous.addSuppressed(error)
        }
    }
    if (failure == null) first?.let { throw it }
}
