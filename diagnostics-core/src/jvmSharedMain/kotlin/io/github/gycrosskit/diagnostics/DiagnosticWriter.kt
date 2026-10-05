package io.github.gycrosskit.diagnostics

import java.io.Closeable
import java.io.IOException
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 实例独占的有界后台写入队列，append 可跨线程调用，flush barrier 报告此前写入失败。
 * 宿主先格式化/脱敏并停止生产日志后 close；不关闭共享 store。
 * @param capacity 排队任务数上限，必须大于 0；队列满时 append/flush 的提交直接失败。
 */
class DiagnosticWriter(private val store: DiagnosticStore, capacity: Int = 1024) : Closeable {
    @Volatile private var writerThread: Thread? = null
    private class WriteFailure(val cause: Throwable) {
        @Volatile var reported = false
    }
    private var failure: WriteFailure? = null
    private val queue = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(capacity), { task ->
            Thread(task, "GYDiagnosticWriter").apply { isDaemon = true; writerThread = this }
        })

    /** 提交脱敏后的日志，不等待落盘；队列满/关闭时抛出 RejectedExecutionException。 */
    fun append(line: String) {
        queue.execute {
            try {
                store.append(line)
                if (failure?.reported == true) failure = null
            } catch (error: Throwable) { failure = WriteFailure(error) }
        }
    }

    /** 等待此前任务；timeoutMillis 必须大于 0，超时/中断/落盘失败抛 IOException，中断标志保留。 */
    fun flush(timeoutMillis: Long = 5000) {
        require(timeoutMillis > 0)
        var observedFailure: WriteFailure? = null
        val barrier = {
            observedFailure = failure
        }
        if (Thread.currentThread() === writerThread) barrier()
        else awaitDiagnosticFlush(queue, timeoutMillis, barrier)
        // 只有 barrier 真正返回后才标记已报告；超时的迟到 barrier 不能吞掉失败。
        observedFailure?.let { it.reported = true; throw IOException("Diagnostic write failed", it.cause) }
    }

    /** 宿主停止生产日志后关闭；不关闭共享的 store。 */
    override fun close() { try { flush() } finally { queue.shutdown() } }
}

/**
 * 同步生成 UTF-8 有界异常原文，cause/suppressed 按 identity 去环；不安装 handler 或写文件。
 * timestampMillis 是 Unix 毫秒，maxBytes 含 truncationMarker；宿主决定保留/脱敏与准入。
 */
fun boundedThrowableReport(
    thread: Thread,
    throwable: Throwable,
    timestampMillis: Long = System.currentTimeMillis(),
    maxBytes: Int = 512 * 1024,
    truncationMarker: String = "\n[CRASH REPORT TRUNCATED]\n",
): String {
    require(maxBytes >= truncationMarker.toByteArray().size)
    val writer = StringWriter()
    val output = BoundedReportWriter(writer, maxBytes, truncationMarker)
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    output.appendLine("timestamp=$timestampMillis")
    output.appendLine("instant=${format.format(Date(timestampMillis))}")
    output.append("thread="); output.appendLine(thread.name)
    output.appendLine("exception=${throwable.javaClass.name}")
    output.append("message="); output.appendLine(throwable.message.orEmpty()); output.appendLine()
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Pair<String, Throwable>>()
    pending.add("" to throwable)
    while (pending.isNotEmpty() && !output.isFull) {
        val (label, error) = pending.removeFirst()
        if (!seen.add(error)) continue
        output.append(label); output.append(error.javaClass.name); output.append(": "); output.appendLine(error.message.orEmpty())
        for (frame in error.stackTrace) { if (output.isFull) break; output.appendLine("\tat $frame") }
        if (output.isFull) { output.append(" "); break }
        error.cause?.let { pending.add("Caused by: " to it) }
        error.suppressed.take(32).forEach { pending.add("Suppressed: " to it) }
    }
    output.finish()
    return writer.toString()
}

/** 独立 barrier 便于宿主复用已有 executor，timeout/中断语义与默认 writer 一致。 */
internal fun awaitDiagnosticFlush(queue: ExecutorService, timeoutMillis: Long, barrier: () -> Unit = {}) {
    try { queue.submit { barrier() }.get(timeoutMillis, TimeUnit.MILLISECONDS) }
    catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Diagnostic flush interrupted", error) }
    catch (error: TimeoutException) { throw IOException("Diagnostic flush timed out", error) }
    catch (error: Exception) { throw IOException("Diagnostic flush failed", error) }
}
