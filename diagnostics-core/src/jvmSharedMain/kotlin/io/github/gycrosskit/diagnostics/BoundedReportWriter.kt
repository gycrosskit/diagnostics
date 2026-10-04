package io.github.gycrosskit.diagnostics

import java.io.Writer

/** 供 ANR 与宿主 Crash 文本格式共用；按 UTF-8 字节预留截断标记，调用方只在结束时调用一次 finish。 */
class BoundedReportWriter(
    private val writer: Writer,
    maxBytes: Int,
    private val truncationMarker: String = "\n[ANR REPORT TRUNCATED: exceeded 256 KiB]\n",
) {
    init { require(maxBytes >= truncationMarker.toByteArray(Charsets.UTF_8).size) }
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
