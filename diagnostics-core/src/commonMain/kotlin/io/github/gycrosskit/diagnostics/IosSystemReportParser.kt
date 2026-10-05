package io.github.gycrosskit.diagnostics

import kotlinx.serialization.json.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** 中性系统报告类型；WATCHDOG 仅来自 crash 原文提示，不替代系统最终诊断。 */
enum class SystemReportKind { CRASH, HANG, WATCHDOG, CPU, DISK, MEMORY }
/** 宿主明确指定的原始 JSON 来源，解析器不会自行采集或识别来源。 */
enum class SystemReportSource { NS_EXCEPTION, METRICKIT }
/** 解析状态；未知但有效 JSON 可成功并返回空报告。 */
enum class ReportParseStatus { PARSED, INVALID_JSON, INPUT_TOO_LARGE }
/**
 * @property status 解析结果，非法/超限输入不抛 JSON 异常。
 * @property reports 中性报告列表，失败时为空；MetricKit 总数最多 100。
 */
data class SystemReportParseResult(val status: ReportParseStatus, val reports: List<NeutralSystemReport>)
/**
 * 只包含宿主原文中的中性字段，不增加展示 ID 或业务建议。
 * @property kind 系统分类。
 * @property source 输入来源。
 * @property section 原文所属数组键或 uncaughtException。
 * @property index 对应 section 中已接受报告的零起点索引。
 * @property timestampMillis Unix 毫秒；未知时间使用调用方 fallback。
 * @property processName 原文进程名/标识，未知为空，最多 1024 字符。
 * @property processId 原文进程号，未知为 0。
 * @property foregroundPage 原文页面/场景，未知为空，最多 1024 字符。
 * @property importance 原文进程状态，未知为空，最多 1024 字符。
 * @property durationMillis 时长毫秒，未知为 0；数字按毫秒，带 sec/s 文本按秒转换。
 * @property description 原文摘要，最多 4096 字符，可能含隐私信息。
 * @property callStack 受详情字符上限控制的线程栈，关闭详情时为空。
 * @property systemTrace 受详情字符上限控制的单报告原文，关闭详情时为空。
 * @property rawText 受详情字符上限控制的完整输入原文，关闭详情时为空。
 */
data class NeutralSystemReport(
    val kind: SystemReportKind, val source: SystemReportSource, val section: String, val index: Int,
    val timestampMillis: Long, val processName: String = "", val processId: Long = 0,
    val foregroundPage: String = "", val importance: String = "", val durationMillis: Long = 0,
    val description: String = "", val callStack: String = "", val systemTrace: String = "", val rawText: String = "",
)

/** 只解析宿主已经采集的 JSON；不启用采集，不生成展示 ID、推断建议或中文文案。 */
object IosSystemReportParser {
    /**
     * 同步纯解析，可跨线程调用，不响应协程取消；不落盘/通知，隐私准入和原文脱敏由宿主负责。
     * fallbackTimestampMillis 为 Unix 毫秒；时间接受整数秒/毫秒、小数秒/毫秒及 ISO8601。
     * 数值位于 ±9_999_999_999 时按秒转换，否则按毫秒；小数毫秒向零截断，非有限/越界值回退。
     * maxInputCharacters 为 1..2 Mi UTF-16 字符，maxDetailCharacters 为 0..256 Ki 字符。
     * includeDetails=false 时保留中性摘要字段，清除 callStack/systemTrace/rawText。
     */
    @OptIn(ExperimentalTime::class)
    fun parse(rawText: String, source: SystemReportSource, fallbackTimestampMillis: Long = 0,
        includeDetails: Boolean = true, maxInputCharacters: Int = 2 * 1024 * 1024,
        maxDetailCharacters: Int = 256 * 1024): SystemReportParseResult {
        require(maxInputCharacters in 1..2 * 1024 * 1024 && maxDetailCharacters in 0..256 * 1024)
        if (rawText.length > maxInputCharacters) return SystemReportParseResult(ReportParseStatus.INPUT_TOO_LARGE, emptyList())
        // JSON parser 前限制结构嵌套，避免畸形输入先耗尽调用栈；引号里的括号不计数。
        var depth = 0; var quoted = false; var escape = false
        for (char in rawText) {
            if (quoted) { if (escape) escape = false else if (char == '\\') escape = true else if (char == '"') quoted = false }
            else when (char) { '"' -> quoted = true; '{', '[' -> if (++depth > 64) return SystemReportParseResult(ReportParseStatus.INVALID_JSON, emptyList()); '}', ']' -> depth-- }
        }
        val root = runCatching { Json.parseToJsonElement(rawText) as? JsonObject }.getOrNull()
            ?: return SystemReportParseResult(ReportParseStatus.INVALID_JSON, emptyList())
        fun detail(value: String) = if (includeDetails) value.take(maxDetailCharacters) else ""
        if (source == SystemReportSource.NS_EXCEPTION) {
            val stack = (root["callStackSymbols"] as? JsonArray)?.take(4096)?.joinToString("\n") { it.text() }.orEmpty()
            val report = NeutralSystemReport(SystemReportKind.CRASH, source, "uncaughtException", 0,
                root["timestamp"].epochMillis() ?: fallbackTimestampMillis,
                processName = root["processName"].text().take(1024),
                description = listOf(root["name"].text(), root["reason"].text()).filter { it.isNotBlank() }.joinToString(": ").take(4096),
                callStack = detail(stack), rawText = detail(rawText))
            return SystemReportParseResult(ReportParseStatus.PARSED, listOf(report))
        }
        val fallback = root.first(TIMESTAMP_KEYS).epochMillis() ?: fallbackTimestampMillis
        val reports = buildList {
            for ((key, initialKind) in ARRAYS) {
                var index = 0
                walk(root) { name, element ->
                    if (name == key && element is JsonArray) for (item in element.take(100)) {
                        if (size >= 100) break
                        val report = item as? JsonObject ?: continue
                        val trace = report.toString()
                        val kind = if (initialKind == SystemReportKind.CRASH && trace.contains("watchdog", true)) SystemReportKind.WATCHDOG else initialKind
                        add(NeutralSystemReport(kind, source, key, index++, report.first(TIMESTAMP_KEYS).epochMillis() ?: fallback,
                            processName = report.first(setOf("processName", "bundleIdentifier")).text().take(1024),
                            processId = (report.first(setOf("processId", "pid")) as? JsonPrimitive)?.longOrNull ?: 0,
                            foregroundPage = report.first(setOf("foregroundPage", "viewController", "scene")).text().take(1024),
                            importance = report.first(setOf("applicationState", "processState")).text().take(1024),
                            durationMillis = duration(report.first(setOf("hangDuration", "duration", "totalCPUTime"))),
                            description = DESCRIPTION_KEYS.map { report.first(setOf(it)).text() }.filter { it.isNotBlank() }.distinct().joinToString(" · ").take(4096),
                            callStack = detail(report.first(setOf("callStackTree", "callStackPerThread", "callStackSymbols"))?.toString().orEmpty()),
                            systemTrace = detail(trace), rawText = detail(rawText)))
                    }
                }
            }
        }
        return SystemReportParseResult(ReportParseStatus.PARSED, reports)
    }
}
private fun JsonElement?.text() = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun walk(root: JsonElement, visit: (String, JsonElement) -> Unit) {
    var budget = 10000
    fun next(value: JsonElement, depth: Int) {
        if (depth > 8 || --budget < 0) return
        when (value) {
            is JsonObject -> for ((key, child) in value) { if (budget <= 0) break; visit(key, child); next(child, depth + 1) }
            is JsonArray -> for (child in value) { if (budget <= 0) break; next(child, depth + 1) }
            else -> Unit
        }
    }
    next(root, 0)
}
private fun JsonObject.first(keys: Set<String>): JsonElement? {
    // 保持 keys 优先级，未知字段或对象类型不做强制 primitive 转换。
    for (target in keys) {
        var found: JsonElement? = null
        walk(this) { key, value -> if (found == null && key == target) found = value }
        if (found != null) return found
    }
    return null
}
@OptIn(ExperimentalTime::class)
private fun JsonElement?.epochMillis(): Long? {
    val value = (this as? JsonPrimitive)?.contentOrNull ?: return null
    value.toLongOrNull()?.let { return if (it in -9_999_999_999L..9_999_999_999L) it * 1000 else it }
    // NSException 采集器使用 Date.timeIntervalSince1970，小数秒不能落回未知时间。
    value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
        val millis = if (it in -9_999_999_999.0..9_999_999_999.0) it * 1000 else it
        if (millis >= Long.MIN_VALUE.toDouble() && millis < Long.MAX_VALUE.toDouble()) return millis.toLong()
    }
    return runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
}
private fun duration(value: JsonElement?): Long {
    val primitive = value as? JsonPrimitive ?: return 0
    primitive.longOrNull?.let { return it }
    val text = primitive.contentOrNull.orEmpty()
    val number = Regex("[0-9]+(?:\\.[0-9]+)?").find(text)?.value?.toDoubleOrNull() ?: return 0
    if (!number.isFinite()) return 0
    return (if (!text.contains("ms", true) && (text.contains("sec", true) || text.contains("s", true))) number * 1000 else number).toLong()
}
private val TIMESTAMP_KEYS = linkedSetOf("timestamp", "timeStamp", "timeStampEnd", "endTime")
private val ARRAYS = listOf("crashDiagnostics" to SystemReportKind.CRASH, "hangDiagnostics" to SystemReportKind.HANG,
    "cpuExceptionDiagnostics" to SystemReportKind.CPU, "diskWriteExceptionDiagnostics" to SystemReportKind.DISK,
    "memoryResourceExceptionDiagnostics" to SystemReportKind.MEMORY)
private val DESCRIPTION_KEYS = setOf("terminationReason", "exceptionType", "exceptionCode", "signal", "hangDuration", "totalCPUTime", "totalWritesCaused")
