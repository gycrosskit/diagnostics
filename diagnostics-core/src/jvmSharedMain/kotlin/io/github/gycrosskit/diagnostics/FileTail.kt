package io.github.gycrosskit.diagnostics
import kotlinx.io.files.Path
import java.io.RandomAccessFile
internal actual fun platformReadTail(path: Path, offset: Long, length: Int): ByteArray = RandomAccessFile(path.toString(), "r").use {
    it.seek(offset)
    ByteArray(length).also(it::readFully)
}
