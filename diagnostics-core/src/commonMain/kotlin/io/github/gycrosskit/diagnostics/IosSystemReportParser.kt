package io.github.gycrosskit.diagnostics

import kotlinx.serialization.json.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class SystemReportKind { CRASH, HANG, WATCHDOG, CPU, DISK, MEMORY }
enum class SystemReportSource { NS_EXCEPTION, METRICKIT }
enum class ReportParseStatus { PARSED, INVALID_JSON, INPUT_TOO_LARGE }
data class SystemReportParseResult(val status: ReportParseStatus, val reports: List<NeutralSystemReport>)
data class NeutralSystemReport(
    val kind: SystemReportKind, val source: SystemReportSource, val section: String, val index: Int,
    val timestampMillis: Long, val processName: String = "", val processId: Long = 0,
    val foregroundPage: String = "", val importance: String = "", val durationMillis: Long = 0,
    val description: String = "", val callStack: String = "", val systemTrace: String = "", val rawText: String = "",
)

/** 只解析宿主已经采集的 JSON；不启用采集，不生成展示 ID、推断建议或中文文案。 */
object IosSystemReportParser {
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
