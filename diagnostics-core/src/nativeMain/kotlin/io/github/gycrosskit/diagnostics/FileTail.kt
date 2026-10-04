package io.github.gycrosskit.diagnostics
import kotlinx.io.files.Path
import kotlinx.cinterop.*
import platform.posix.*
@OptIn(ExperimentalForeignApi::class)
internal actual fun platformReadTail(path: Path, offset: Long, length: Int): ByteArray {
    val file = checkNotNull(fopen(path.toString(), "rb")) { "Snapshot source unavailable" }
    try {
        check(fseek(file, offset, SEEK_SET) == 0) { "Cannot seek snapshot source" }
        if (length == 0) return ByteArray(0)
        val bytes = ByteArray(length)
        val read = bytes.usePinned { fread(it.addressOf(0), 1u, length.toULong(), file).toInt() }
        check(read == length && ferror(file) == 0) { "Snapshot source shortened" }
        return bytes
    } finally { fclose(file) }
}
