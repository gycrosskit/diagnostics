package io.github.gycrosskit.diagnostics

import java.io.Writer

/**
 * ANR/Crash 共用的 UTF-8 字节有界文本写入器；同一实例须串行调用。
 * 不关闭/flush writer，调用方结束时只调用一次 finish，并负责释放 writer。
 * @param maxBytes 总字节上限，必须至少容纳 truncationMarker。
 * @param truncationMarker 截断时写入的完整标记，其字节数预先从 payload 额度扣除。
 */
class BoundedReportWriter(
    private val writer: Writer,
    maxBytes: Int,
    private val truncationMarker: String = "\n[ANR REPORT TRUNCATED: exceeded 256 KiB]\n",
) {
    init { require(maxBytes >= truncationMarker.toByteArray(Charsets.UTF_8).size) }
    private var remainingPayloadBytes =
        (maxBytes - truncationMarker.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
    private var truncated = false

    /** 写入文本及换行，均计入 payload 字节额度。 */
    fun appendLine(value: String = "") {
        append(value)
        append("\n")
    }

    /** 写入标题、正文及分隔空行；内容未经脱敏，隐私筛选由宿主负责。 */
    fun appendSection(title: String, value: String) {
        appendLine("===== $title =====")
        appendLine(value)
        appendLine()
    }

    /** 结束格式化，若截断则补标记；不关闭/flush writer，不得重复调用。 */
    fun finish() {
        if (truncated) writer.write(truncationMarker)
    }

    /** payload 额度是否耗尽，不包括预留的截断标记。 */
    val isFull: Boolean get() = remainingPayloadBytes <= 0

    /** 在完整 Unicode 字符边界按剩余 UTF-8 字节截断，writer 失败向调用方传播。 */
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
