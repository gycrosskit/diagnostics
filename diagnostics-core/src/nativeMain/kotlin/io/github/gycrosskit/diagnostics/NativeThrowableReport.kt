package io.github.gycrosskit.diagnostics

import kotlin.experimental.ExperimentalNativeApi

/** Native 异常按 UTF-8 总预算格式化；不先构造完整堆栈，cause/suppressed 共用节点与深度限额。 */
@OptIn(ExperimentalNativeApi::class)
fun boundedNativeThrowableReport(error: Throwable, maxBytes: Int = 512 * 1024): String {
    val output = NativeThrowableText(maxBytes)
    val visited = mutableListOf<Throwable>()
    fun append(current: Throwable, prefix: String, depth: Int) {
        if (output.exhausted) return
        if (depth >= 16 || visited.size >= 16 || visited.any { it === current }) {
            output.truncate()
            return
        }
        visited += current
        output.append(prefix)
        output.append(current::class.simpleName ?: "Throwable")
        current.message?.let { output.append(": "); output.append(it) }
        output.append("\n")
        if (output.exhausted) return
        val frames = current.getStackTrace()
        for (index in 0 until minOf(frames.size, 100)) {
            output.append("    at ")
            output.append(frames[index])
            output.append("\n")
            if (output.exhausted) return
        }
        if (frames.size > 100) {
            output.truncate()
        }
        for ((index, suppressed) in current.suppressedExceptions.withIndex()) {
            if (index >= 16 || output.exhausted || visited.size >= 16) {
                output.truncate()
                break
            }
            append(suppressed, "Suppressed: ", depth + 1)
        }
        current.cause?.let { append(it, "Caused by: ", depth + 1) }
    }
    append(error, "", 0)
    return output.finish()
}

private class NativeThrowableText(maxBytes: Int) {
    private val marker = "\n[THROWABLE REPORT TRUNCATED]\n"
    init { require(maxBytes >= marker.encodeToByteArray().size) { "maxBytes must fit the truncation marker" } }
    private val text = StringBuilder()
    private var remaining = maxBytes - marker.encodeToByteArray().size
    private var truncated = false
    var exhausted = false
        private set

    fun truncate() { truncated = true }

    fun append(value: String) {
        if (exhausted) return
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val character = value[index]
            val pair = character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()
            val width = when {
                pair -> 4
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                else -> 3
            }
            if (bytes + width > remaining) break
            bytes += width
            index += if (pair) 2 else 1
        }
        text.append(value, 0, index)
        remaining -= bytes
        if (index < value.length) {
            truncate()
            exhausted = true
        }
    }

    fun finish(): String = if (truncated) text.append(marker).toString() else text.toString()
}
