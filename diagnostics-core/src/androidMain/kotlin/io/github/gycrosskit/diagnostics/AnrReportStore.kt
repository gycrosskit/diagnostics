package io.github.gycrosskit.diagnostics

import android.content.Context
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** ANR 来源：系统历史退出记录是最终事实，看门狗记录是进程被杀前的现场补充。 */
enum class AnrReportSource {
    SYSTEM,
    WATCHDOG,
}

/**
 * 一份可持久化的 ANR 诊断报告。
 *
 * [systemTrace] 来自 Android 11+ 的 ApplicationExitInfo；[mainThreadStack] 与
 * [allThreadStacks] 来自系统 trace 或看门狗采样。字段保持纯文本，诊断 ZIP 解压后无需专用工具即可查看。
 */
data class AnrReport(
    val source: AnrReportSource,
    val timestampMillis: Long,
    val processName: String = "",
    val pid: Int = 0,
    val foregroundActivity: String = "",
    val importance: String = "",
    val pssKb: Long = 0L,
    val rssKb: Long = 0L,
    val cpuTimeMillis: Long = 0L,
    val blockedDurationMillis: Long = 0L,
    val description: String = "",
    val suspectedReason: String = "",
    val mainThreadStack: String = "",
    val allThreadStacks: String = "",
    val systemTrace: String = "",
)

/** ANR 列表只读取文件头，避免概览页一次把多份完整线程栈放进 Compose 状态。 */
data class AnrReportSummary(
    val id: String,
    val source: AnrReportSource,
    val timestampMillis: Long,
    val processName: String,
    val foregroundActivity: String,
    val blockedDurationMillis: Long,
    val description: String,
    val suspectedReason: String,
)

/** ANR 详情同时保留结构化字段和原始文本，便于页面分析、复制及外部工具二次处理。 */
data class StoredAnrReport(
    val id: String,
    val report: AnrReport,
    val rawText: String,
)

/**
 * ANR 报告文件仓库。
 *
 * 与崩溃文件相同，写入过程使用临时文件加原子重命名；系统 ANR 使用稳定文件名，因此同一条
 * ApplicationExitInfo 在多次启动时不会重复保存。
 */
class AnrReportStore(
    private val reportDirectory: File,
) {
    constructor(context: Context) : this(
        reportDirectory = File(context.noBackupFilesDir, ANR_DIRECTORY_NAME),
    )

    /** 保存报告；按固定额度流式写盘，诊断数据异常时返回 null，不影响业务进程。 */
    @Synchronized
    fun record(report: AnrReport): File? {
        var temporary: File? = null
        return try {
            check(reportDirectory.exists() || reportDirectory.mkdirs()) { "无法创建 ANR 目录" }
            val target = File(reportDirectory, report.fileName())
            if (target.isFile) return target
            temporary = File(reportDirectory, "${target.name}.tmp")
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                report.writeTo(writer)
            }
            check(temporary.renameTo(target)) { "无法完成 ANR 文件写入" }
            trimOldReports()
            target
        } catch (_: Throwable) {
            // ANR 诊断不能成为二次崩溃源；临时文件可安全删除，调用方只记录轻量失败日志。
            temporary?.delete()
            null
        }
    }

    /** 返回可上传或打包的稳定报告文件，不包含写入中的临时文件。 */
    @Synchronized
    fun pendingReportFiles(): List<File> = reportFiles().sortedBy(File::getName)

    /** 仅读取报告头，用于 ANR 列表和概览计数。 */
    @Synchronized
    fun summaries(): List<AnrReportSummary> = reportFiles()
        .mapNotNull(::readSummary)
        .sortedByDescending(AnrReportSummary::timestampMillis)

    /** 按受控文件名读取详情，拒绝路径穿越和临时文件。 */
    @Synchronized
    fun report(id: String): StoredAnrReport? {
        if (id != File(id).name || !id.startsWith(ANR_FILE_PREFIX) || !id.endsWith(FILE_SUFFIX)) {
            return null
        }
        val file = File(reportDirectory, id)
        if (!file.isFile) return null
        return runCatching {
            val rawText = file.readTextLimited(MAX_REPORT_CHARACTERS)
            StoredAnrReport(id = id, report = parseReport(rawText), rawText = rawText)
        }.getOrNull()
    }

    private fun reportFiles(): List<File> = reportDirectory.listFiles()
        .orEmpty()
        .filter { file ->
            file.isFile &&
                file.name.startsWith(ANR_FILE_PREFIX) &&
                file.name.endsWith(FILE_SUFFIX)
        }

    private fun readSummary(file: File): AnrReportSummary? = runCatching {
        val headers = file.bufferedReader(Charsets.UTF_8).use { reader ->
            buildMap {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    val separator = line.indexOf('=')
                    if (separator > 0) put(line.substring(0, separator), line.substring(separator + 1))
                }
            }
        }
        AnrReportSummary(
            id = file.name,
            source = headers[HEADER_SOURCE].toAnrSource(),
            timestampMillis = headers[HEADER_TIMESTAMP].orEmpty().toLongOrNull() ?: 0L,
            processName = headers[HEADER_PROCESS].orEmpty(),
            foregroundActivity = headers[HEADER_ACTIVITY].orEmpty(),
            blockedDurationMillis = headers[HEADER_BLOCKED_DURATION].orEmpty().toLongOrNull() ?: 0L,
            description = headers[HEADER_DESCRIPTION].orEmpty(),
            suspectedReason = headers[HEADER_SUSPECTED_REASON].orEmpty(),
        )
    }.getOrNull()

    private fun trimOldReports() {
        reportFiles()
            .sortedByDescending(File::lastModified)
            .drop(MAX_ANR_FILES)
            .forEach(File::delete)
    }

    internal companion object {
        const val ANR_DIRECTORY_NAME = "anrs"
        const val ANR_FILE_PREFIX = "anr_"
        const val FILE_SUFFIX = ".txt"
        const val MAX_ANR_FILES = 10
        const val MAX_REPORT_BYTES = 256 * 1024
        const val MAX_REPORT_CHARACTERS = MAX_REPORT_BYTES

        const val HEADER_SOURCE = "source"
        const val HEADER_TIMESTAMP = "timestamp"
        const val HEADER_PROCESS = "processName"
        const val HEADER_ACTIVITY = "foregroundActivity"
        const val HEADER_BLOCKED_DURATION = "blockedDurationMillis"
        const val HEADER_DESCRIPTION = "description"
        const val HEADER_SUSPECTED_REASON = "suspectedReason"
    }
}

/** 用主线程栈和系统 trace 给出可读的首要排查方向，不替代人工确认。 */
fun analyzeAnr(mainThreadStack: String, systemTrace: String = ""): String {
    fun evidenceContains(value: String, ignoreCase: Boolean = false): Boolean =
        mainThreadStack.contains(value, ignoreCase) || systemTrace.contains(value, ignoreCase)

    return when {
        evidenceContains("UnionInsets.equals") ||
            evidenceContains("InsetsPaddingModifier.setConsumedInsets") ->
            "疑似 Compose WindowInsets/布局修饰符递归更新"
        evidenceContains("kotlinx.coroutines.runBlocking") ->
            "疑似主线程执行 runBlocking 等待异步任务"
        evidenceContains("java.lang.Thread.sleep") ->
            "疑似主线程主动休眠"
        evidenceContains("android.database.sqlite", ignoreCase = true) ->
            "疑似主线程执行数据库读写"
        evidenceContains("okhttp", ignoreCase = true) ||
            evidenceContains("io.ktor", ignoreCase = true) ||
            evidenceContains("SocketInputStream") ->
            "疑似主线程同步等待网络或 Socket I/O"
        evidenceContains("android.os.BinderProxy.transact") ->
            "疑似主线程等待 Binder 调用返回"
        Regex("state=.*BLOCKED", RegexOption.IGNORE_CASE).containsMatchIn(mainThreadStack) ->
            "疑似主线程等待锁，需结合持锁线程栈分析"
        else -> "主线程超过响应阈值，需从主线程首个业务栈帧继续定位"
    }
}

/** 从 Android ANR trace 中抽取 main 线程段；格式未知时保留 trace 前部作为兜底。 */
fun extractMainThreadTrace(systemTrace: String): String {
    val lines = systemTrace.lines()
    val start = lines.indexOfFirst { it.trimStart().startsWith("\"main\"") }
    if (start < 0) return lines.take(MAX_MAIN_TRACE_LINES).joinToString("\n")
    val end = (start + 1 until lines.size).firstOrNull { index ->
        lines[index].trimStart().startsWith('"')
    } ?: lines.size
    return lines.subList(start, end).take(MAX_MAIN_TRACE_LINES).joinToString("\n").trim()
}

private fun AnrReport.fileName(): String {
    val sourceName = source.name.lowercase(Locale.US)
    val pidSuffix = pid.takeIf { it > 0 }?.let { "_$it" }.orEmpty()
    return "${AnrReportStore.ANR_FILE_PREFIX}${sourceName}_${timestampMillis}$pidSuffix" +
        AnrReportStore.FILE_SUFFIX
}

private fun AnrReport.writeTo(writer: Writer) {
    val output = BoundedReportWriter(writer, AnrReportStore.MAX_REPORT_BYTES)
    output.appendLine("formatVersion=1")
    output.appendLine("source=${source.name}")
    output.appendLine("timestamp=$timestampMillis")
    output.appendLine("instant=${formatAnrTime(timestampMillis)}")
    output.appendLine("processName=${processName.singleLine()}")
    output.appendLine("pid=$pid")
    output.appendLine("foregroundActivity=${foregroundActivity.singleLine()}")
    output.appendLine("importance=${importance.singleLine()}")
    output.appendLine("pssKb=$pssKb")
    output.appendLine("rssKb=$rssKb")
    output.appendLine("cpuTimeMillis=$cpuTimeMillis")
    output.appendLine("blockedDurationMillis=$blockedDurationMillis")
    output.appendLine("description=${description.singleLine()}")
    output.appendLine("suspectedReason=${suspectedReason.singleLine()}")
    output.appendLine()
    output.appendSection("MAIN THREAD", mainThreadStack)
    output.appendSection("ALL THREADS", allThreadStacks)
    output.appendSection("SYSTEM TRACE", systemTrace)
    output.finish()
}

private fun parseReport(rawText: String): AnrReport {
    val headerEnd = rawText.indexOf("\n\n").let { if (it >= 0) it else rawText.length }
    val headers = rawText.substring(0, headerEnd).lineSequence().mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
    }.toMap()
    return AnrReport(
        source = headers[AnrReportStore.HEADER_SOURCE].toAnrSource(),
        timestampMillis = headers[AnrReportStore.HEADER_TIMESTAMP].orEmpty().toLongOrNull() ?: 0L,
        processName = headers[AnrReportStore.HEADER_PROCESS].orEmpty(),
        pid = headers["pid"].orEmpty().toIntOrNull() ?: 0,
        foregroundActivity = headers[AnrReportStore.HEADER_ACTIVITY].orEmpty(),
        importance = headers["importance"].orEmpty(),
        pssKb = headers["pssKb"].orEmpty().toLongOrNull() ?: 0L,
        rssKb = headers["rssKb"].orEmpty().toLongOrNull() ?: 0L,
        cpuTimeMillis = headers["cpuTimeMillis"].orEmpty().toLongOrNull() ?: 0L,
        blockedDurationMillis = headers[AnrReportStore.HEADER_BLOCKED_DURATION]
            .orEmpty().toLongOrNull() ?: 0L,
        description = headers[AnrReportStore.HEADER_DESCRIPTION].orEmpty(),
        suspectedReason = headers[AnrReportStore.HEADER_SUSPECTED_REASON].orEmpty(),
        mainThreadStack = rawText.section("MAIN THREAD"),
        allThreadStacks = rawText.section("ALL THREADS"),
        systemTrace = rawText.section("SYSTEM TRACE"),
    )
}

private fun String.section(title: String): String {
    val marker = "===== $title =====\n"
    val start = indexOf(marker)
    if (start < 0) return ""
    val contentStart = start + marker.length
    val end = indexOf("\n===== ", contentStart).let { if (it >= 0) it else length }
    return substring(contentStart, end).trim()
}

private fun String?.toAnrSource(): AnrReportSource = runCatching {
    AnrReportSource.valueOf(this.orEmpty())
}.getOrDefault(AnrReportSource.WATCHDOG)

private fun String.singleLine(): String = buildString(minOf(length, MAX_HEADER_CHARACTERS)) {
    for (index in 0 until minOf(this@singleLine.length, MAX_HEADER_CHARACTERS)) {
        val character = this@singleLine[index]
        append(if (character == '\r' || character == '\n') ' ' else character)
    }
}.trim()

private fun File.readTextLimited(maxCharacters: Int): String {
    val output = StringBuilder(minOf(maxCharacters, 16 * 1024))
    bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(8 * 1024)
        while (output.length < maxCharacters) {
            val count = reader.read(buffer, 0, minOf(buffer.size, maxCharacters - output.length))
            if (count < 0) break
            output.append(buffer, 0, count)
        }
    }
    return output.toString()
}

/** 供 ANR 与宿主 Crash 文本格式共用；按 UTF-8 字节预留截断标记，调用方只在结束时调用一次 finish。 */
class BoundedReportWriter(
    private val writer: Writer,
    maxBytes: Int,
    private val truncationMarker: String = TRUNCATION_MARKER,
) {
    private var remainingPayloadBytes =
        (maxBytes - truncationMarker.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
    private var truncated = false

    fun appendLine(value: String = "") {
        append(value)
        append("\n")
    }

    fun appendSection(title: String, value: String) {
        appendLine("===== $title =====")
        appendLine(value)
        appendLine()
    }

    fun finish() {
        if (truncated) writer.write(truncationMarker)
    }

    val isFull: Boolean get() = remainingPayloadBytes <= 0

    fun append(value: String) {
        if (value.isEmpty()) return
        if (remainingPayloadBytes <= 0) {
            truncated = true
            return
        }
        var characterCount = 0
        var byteCount = 0
        while (characterCount < value.length) {
            val current = value[characterCount]
            val surrogatePair = current.isHighSurrogate() &&
                characterCount + 1 < value.length && value[characterCount + 1].isLowSurrogate()
            val characterBytes = when {
                surrogatePair -> 4
                current.code <= 0x7F -> 1
                current.code <= 0x7FF -> 2
                else -> 3
            }
            if (byteCount + characterBytes > remainingPayloadBytes) break
            byteCount += characterBytes
            characterCount += if (surrogatePair) 2 else 1
        }
        if (characterCount > 0) writer.write(value, 0, characterCount)
        remainingPayloadBytes -= byteCount
        if (characterCount < value.length) truncated = true
    }
}

private fun formatAnrTime(timestampMillis: Long): String = SimpleDateFormat(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    Locale.US,
).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(timestampMillis))

private const val MAX_MAIN_TRACE_LINES = 160
private const val MAX_HEADER_CHARACTERS = 2 * 1024
private const val TRUNCATION_MARKER = "\n[ANR REPORT TRUNCATED: exceeded 256 KiB]\n"
