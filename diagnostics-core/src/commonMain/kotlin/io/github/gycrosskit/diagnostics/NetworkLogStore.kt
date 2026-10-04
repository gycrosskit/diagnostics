package io.github.gycrosskit.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

enum class NetworkLogKind { REQUEST, RESPONSE, FAILURE, OTHER }
data class NetworkLogRecord<C>(val id: Long, val timestampMillis: Long, val context: C,
    val kind: NetworkLogKind, val url: String, val statusCode: Int?, val summary: String,
    val message: String, val truncated: Boolean)

/** 可选页面记录机制。宿主先执行准入和脱敏；context 只存宿主传入的环境关联。 */
@OptIn(ExperimentalTime::class)
class NetworkLogStore<C>(val maxRecords: Int = 100, val maxMessageCharacters: Int = 32 * 1024) {
    private val lock = StoreLock()
    private var nextId = 0L
    private val state = MutableStateFlow<List<NetworkLogRecord<C>>>(emptyList())
    val records: StateFlow<List<NetworkLogRecord<C>>> = state.asStateFlow()
    init { require(maxRecords in 1..100 && maxMessageCharacters in 1..32 * 1024) }
    fun record(message: String, context: C, timestampMillis: Long = Clock.System.now().toEpochMilliseconds()) = lock.locked {
        if (message.isBlank()) return@locked
        val bounded = message.take(maxMessageCharacters)
        val kind = classifyNetworkLog(bounded)
        val entry = NetworkLogRecord(++nextId, timestampMillis, context, kind,
            parseNetworkLogUrl(bounded, kind), parseNetworkResponseStatus(bounded, kind),
            bounded.lineSequence().firstOrNull().orEmpty().take(240), bounded, message.length > bounded.length)
        state.value = (state.value + entry).takeLast(maxRecords)
    }
    fun clear() = lock.locked { state.value = emptyList() }
}
fun classifyNetworkLog(message: String): NetworkLogKind {
    val first = message.lineSequence().firstOrNull().orEmpty()
    return when {
        first.startsWith("REQUEST:") -> NetworkLogKind.REQUEST
        first.startsWith("RESPONSE:") -> NetworkLogKind.RESPONSE
        first.contains("failed with exception", ignoreCase = true) -> NetworkLogKind.FAILURE
        else -> NetworkLogKind.OTHER
    }
}
fun parseNetworkLogUrl(message: String, kind: NetworkLogKind = classifyNetworkLog(message)): String = when (kind) {
    NetworkLogKind.REQUEST -> message.lineSequence().firstOrNull().orEmpty().substringAfter("REQUEST:", "").trim()
    NetworkLogKind.RESPONSE -> message.lineSequence().map(String::trim).firstOrNull { it.startsWith("FROM:") }.orEmpty().substringAfter("FROM:", "").trim()
    else -> ""
}
fun parseNetworkResponseStatus(message: String, kind: NetworkLogKind = classifyNetworkLog(message)): Int? =
    if (kind == NetworkLogKind.RESPONSE) message.lineSequence().firstOrNull().orEmpty()
        .substringAfter("RESPONSE:", "").trim().substringBefore(' ').toIntOrNull() else null
