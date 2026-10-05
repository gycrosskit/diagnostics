package io.github.gycrosskit.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 从 Ktor 风格首行识别的日志分类；未知格式保留为 OTHER。 */
enum class NetworkLogKind { REQUEST, RESPONSE, FAILURE, OTHER }
/**
 * @property id 实例内单调递增标识，clear 不重置。
 * @property timestampMillis 宿主提供或记录时的 Unix 毫秒。
 * @property context 宿主环境关联值；本库不复制或脱敏 context，宿主应使用不可变值。
 * @property kind 首行识别的类型。
 * @property url 请求首行/响应 FROM 字段，未知为空，可能含私密 query。
 * @property statusCode 响应首行的整数状态码，未知为 null。
 * @property summary 首行前 240 个字符。
 * @property message 有界原文，宿主必须先移除凭据和个人信息。
 * @property truncated 输入是否超过字符额度。
 * @property requestId 可选采集适配生成的同次发送标识；旧文本/截断掉关联行时为 null。
 * @property durationMillis 到响应头/失败的单调时钟耗时；旧文本/截断掉关联行时为 null。
 */
data class NetworkLogRecord<C>(val id: Long, val timestampMillis: Long, val context: C,
    val kind: NetworkLogKind, val url: String, val statusCode: Int?, val summary: String,
    val message: String, val truncated: Boolean) {
    // 不扩充 data class 主构造，保留旧 constructor/copy 的二进制入口。
    val requestId: Long? get() = correlationLine()?.substringAfter('[')?.substringBefore(']')?.toLongOrNull()
    val durationMillis: Long? get() = correlationLine()?.substringAfter("] ", "")?.substringBefore("ms")?.toLongOrNull()
    private fun correlationLine(): String? = message.lineSequence().take(3).firstOrNull { it.startsWith('[') && it.contains("] ") }
}

/**
 * 可跨线程的内存日志环，实例锁串行提交；不写文件、不发通知。
 * 宿主先执行准入/脱敏；clear 释放记录引用，生产停止后由宿主释放实例。
 * @property maxRecords 保留最新记录数（1..100）。
 * @property maxMessageCharacters 每条 UTF-16 字符上限（1..32 Ki），不代表字节上限。
 */
@OptIn(ExperimentalTime::class)
class NetworkLogStore<C>(val maxRecords: Int = 100, val maxMessageCharacters: Int = 32 * 1024) {
    // StateFlow 的 Unconfined 观察者可在发布时同步清空；Native 也必须允许同线程重入。
    private val lock = StoreLock(recursive = true)
    private var nextId = 0L
    private var nextRequestId = 0L
    private val state = MutableStateFlow<List<NetworkLogRecord<C>>>(emptyList())
    /** 最新只读列表；StateFlow 观察者负责自身 scope 生命周期。 */
    val records: StateFlow<List<NetworkLogRecord<C>>> = state.asStateFlow()
    init { require(maxRecords in 1..100 && maxMessageCharacters in 1..32 * 1024) }
    /** 忽略空白输入，按字符截断并同步提交；不保留超过容量的旧记录。 */
    fun record(message: String, context: C, timestampMillis: Long = Clock.System.now().toEpochMilliseconds()) = lock.locked {
        if (message.isBlank()) return@locked
        val bounded = message.take(maxMessageCharacters)
        val kind = classifyNetworkLog(bounded)
        val entry = NetworkLogRecord(++nextId, timestampMillis, context, kind,
            parseNetworkLogUrl(bounded, kind), parseNetworkResponseStatus(bounded, kind),
            bounded.lineSequence().firstOrNull().orEmpty().take(240), bounded, message.length > bounded.length)
        state.value = (state.value + entry).takeLast(maxRecords)
    }
    /** 同步清空内存记录，不重置递增 id，也不取消观察者。 */
    fun clear() = lock.locked { state.value = emptyList() }
    internal fun allocateRequestId(): Long = lock.locked { ++nextRequestId }
}
/** 只识别首行 REQUEST:/RESPONSE:/失败关键字，不解析 URL 或触发网络。 */
fun classifyNetworkLog(message: String): NetworkLogKind {
    val first = message.lineSequence().firstOrNull().orEmpty()
    return when {
        first.startsWith("REQUEST:") -> NetworkLogKind.REQUEST
        first.startsWith("RESPONSE:") -> NetworkLogKind.RESPONSE
        first.contains("failed with exception", ignoreCase = true) -> NetworkLogKind.FAILURE
        else -> NetworkLogKind.OTHER
    }
}
/** 请求取首行，响应取首个 FROM: 行；其他类型返回空，不校验或脱敏 URL。 */
fun parseNetworkLogUrl(message: String, kind: NetworkLogKind = classifyNetworkLog(message)): String = when (kind) {
    NetworkLogKind.REQUEST -> message.lineSequence().firstOrNull().orEmpty().substringAfter("REQUEST:", "").trim()
    NetworkLogKind.RESPONSE -> message.lineSequence().map(String::trim).firstOrNull { it.startsWith("FROM:") }.orEmpty().substringAfter("FROM:", "").trim()
    else -> ""
}
/** 只返回 RESPONSE: 首行的整数状态；无效/其他类型为 null，不推断协议范围。 */
fun parseNetworkResponseStatus(message: String, kind: NetworkLogKind = classifyNetworkLog(message)): Int? =
    if (kind == NetworkLogKind.RESPONSE) message.lineSequence().firstOrNull().orEmpty()
        .substringAfter("RESPONSE:", "").trim().substringBefore(' ').toIntOrNull() else null
