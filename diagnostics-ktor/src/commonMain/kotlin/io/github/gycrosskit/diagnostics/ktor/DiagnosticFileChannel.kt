package io.github.gycrosskit.diagnostics.ktor

import io.github.gycrosskit.diagnostics.DiagnosticBatch
import io.github.gycrosskit.diagnostics.DiagnosticStore
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.WriterJob
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** 冻结文件的流与释放屏障；完成只证明本地 EOF/close 成功，不证明服务端接收。 */
class DiagnosticFileChannel internal constructor(private val writer: WriterJob) {
    val channel: ByteReadChannel get() = writer.channel
    val isClosed: Boolean get() = writer.job.isCompleted
    val isComplete: Boolean get() = isClosed && !writer.job.isCancelled && channel.closedCause == null

    /** 只等资源释放，用于重试时等待之前失败的流；不把失败变为完整读取。 */
    suspend fun awaitClosed() { writer.job.join() }

    /** 等待 reader 关闭；读取、关闭或取消失败向调用方传播，绝不确认或删除批次。 */
    suspend fun awaitComplete() {
        awaitClosed()
        channel.closedCause?.let { throw it }
        if (writer.job.isCancelled) throw CancellationException("诊断文件读取已取消")
        check(isComplete) { "诊断文件未完整读取并关闭" }
    }
}

/**
 * 在宿主持有的上传 scope 中逐块读取；scope 应使用后台 dispatcher，并在请求结束时取消/等待子任务。
 * reader 在协程开始后才打开，避免调度前取消泄漏句柄。消费者取消 channel 后停止读取。
 */
fun DiagnosticStore.openFileChannel(
    batch: DiagnosticBatch,
    fileId: String,
    scope: CoroutineScope,
): DiagnosticFileChannel = DiagnosticFileChannel(scope.writer {
    coroutineContext.ensureActive()
    channel.closedCause?.let { throw it }
    val reader = openFile(batch, fileId)
    copyDiagnosticFile(channel, { reader.read() }, reader::close)
})

internal suspend fun copyDiagnosticFile(channel: ByteWriteChannel, read: () -> ByteArray, close: () -> Unit) {
    var failure: Throwable? = null
    try {
        while (true) {
            coroutineContext.ensureActive()
            channel.closedCause?.let { throw it }
            val bytes = read()
            if (bytes.isEmpty()) break
            channel.writeFully(bytes)
        }
        coroutineContext.ensureActive()
        channel.closedCause?.let { throw it }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            close()
        } catch (error: Throwable) {
            if (failure == null) throw error
            failure.addSuppressed(error)
        }
    }
    coroutineContext.ensureActive()
    channel.closedCause?.let { throw it }
}
