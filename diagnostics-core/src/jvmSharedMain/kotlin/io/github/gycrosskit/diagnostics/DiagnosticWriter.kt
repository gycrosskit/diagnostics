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

/** 唯一有界后台写入队列。宿主先格式化/脱敏，再 append；flush barrier 报告之前的写入失败。 */
class DiagnosticWriter(private val store: DiagnosticStore, capacity: Int = 1024) : Closeable {
    @Volatile private var writerThread: Thread? = null
    @Volatile private var failure: Throwable? = null
    private val queue = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(capacity), { task ->
            Thread(task, "GYDiagnosticWriter").apply { isDaemon = true; writerThread = this }
        })

    fun append(line: String) {
        queue.execute {
            try { store.append(line); failure = null } catch (error: Throwable) { failure = error }
        }
    }

    /** timeout 和中断均向宿主报告；不会把有失败的批次伪装成已刷新。 */
    fun flush(timeoutMillis: Long = 5000) {
        require(timeoutMillis > 0)
        if (Thread.currentThread() !== writerThread) awaitDiagnosticFlush(queue, timeoutMillis)
        failure?.let { throw IOException("Diagnostic write failed", it) }
    }

    /** 宿主停止生产日志后关闭；不关闭共享的 store。 */
    override fun close() { try { flush() } finally { queue.shutdown() } }
}

/** 不调用 stackTraceToString/printStackTrace；cause/suppressed 按 identity 去环且 UTF-8 有界。 */
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
internal fun awaitDiagnosticFlush(queue: ExecutorService, timeoutMillis: Long) {
    try { queue.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS) }
    catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Diagnostic flush interrupted", error) }
    catch (error: TimeoutException) { throw IOException("Diagnostic flush timed out", error) }
    catch (error: Exception) { throw IOException("Diagnostic flush failed", error) }
}
