package io.github.gycrosskit.diagnostics

import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/** 路径只用于宿主注入的私有文件操作；不携带账号、设备或业务 metadata。 */
data class DiagnosticSnapshotFile(val path: String, val name: String, val size: Long)
data class LegacyDiagnosticSource(val directory: String, val prefixes: List<String>, val frozen: Boolean = false)

/** 通用只读文件机制，不持有日志队列或批次 owner。 */
object DiagnosticFiles {
    private val fs get() = SystemFileSystem
    @Throws(Exception::class)
    fun list(directory: String, prefixes: List<String>): List<DiagnosticSnapshotFile> {
        val requested = checkedPath(directory)
        if (!fs.exists(requested)) return emptyList()
        val root = fs.resolve(requested)
        return fs.list(root).filter { path ->
            !path.name.endsWith(".tmp") && prefixes.any { path.name.startsWith(it) } &&
                fs.metadataOrNull(path)?.isRegularFile == true && fs.resolve(path) == path
        }.map { DiagnosticSnapshotFile(it.toString(), it.name, fs.metadataOrNull(it)?.size ?: 0) }
            .filter { it.size > 0 }.sortedBy { it.name }
    }

    /** 直接 seek 到尾部，不分配整个文件；超限首行丢弃，避免 UTF-8 半字符进入摘要。 */
    @Throws(Exception::class)
    fun readTail(file: DiagnosticSnapshotFile, maxBytes: Int, dropPartialFirstLine: Boolean = false): ByteArray {
        require(maxBytes in 1..1024 * 1024)
        val path = checkedFile(file)
        val skip = (file.size - maxBytes).coerceAtLeast(0)
        val bytes = platformReadTail(path, skip, minOf(file.size, maxBytes.toLong()).toInt())
        return if (skip > 0 && dropPartialFirstLine) {
            val newline = bytes.indexOf(10)
            if (newline < 0) ByteArray(0) else bytes.copyOfRange(newline + 1, bytes.size)
        } else bytes
    }

    @Throws(Exception::class)
    fun openSnapshot(file: DiagnosticSnapshotFile): DiagnosticFileReader = DiagnosticFileReader(open(file), file.size, exactLength = false)

    /** 完整流式比较；指纹仅作为标识，不作为删除授权。 */
    @Throws(Exception::class)
    fun sameContents(first: String, second: String): Boolean {
        val left = checkedPath(first); val right = checkedPath(second)
        if (!safeFile(left) || !safeFile(right)) return false
        val size = fs.metadataOrNull(left)?.size ?: return false
        if (size != fs.metadataOrNull(right)?.size) return false
        return fs.source(left).buffered().use { a -> fs.source(right).buffered().use { b ->
            var remaining = size
            while (remaining > 0) {
                val count = minOf(remaining, 16 * 1024L).toInt()
                if (!a.readByteArray(count).contentEquals(b.readByteArray(count))) return false
                remaining -= count
            }
            a.exhausted() && b.exhausted()
        } }
    }

    /** 保持历史 SHA-256 内容标识；真正删除仍逐字节比较。 */
    @Throws(Exception::class)
    fun fingerprint(file: DiagnosticSnapshotFile): String = open(file).use { source ->
        val digest = Sha256()
        var remaining = file.size
        while (remaining > 0) {
            val count = minOf(remaining, 16 * 1024L).toInt()
            digest.update(source.readByteArray(count)); remaining -= count
        }
        digest.finish()
    }

    /** 先打开所有源文件，按快照长度流式导出；active/frozen 同名各自保留，失败删除临时产物。 */
    @Throws(Exception::class)
    fun exportTextSnapshot(files: List<DiagnosticSnapshotFile>, destination: String, header: String = ""): Long {
        val target = checkedPath(destination)
        require(files.none { it.path == target.toString() })
        require(!fs.exists(target)) { "Destination already exists" }
        val inputs = mutableListOf<Pair<DiagnosticSnapshotFile, Source>>()
        val temporary = Path("$target.tmp")
        require(!fs.exists(temporary)) { "Temporary destination already exists" }
        try {
            files.forEach { inputs += it to open(it) }
            fs.sink(temporary).buffered().use { output ->
                output.writeString(header)
                for ((file, input) in inputs) {
                    output.writeString("\n===== ${file.name} (${file.size} bytes) =====\n")
                    var remaining = file.size
                    while (remaining > 0) {
                        val count = minOf(remaining, 16 * 1024L).toInt()
                        output.write(input.readByteArray(count)); remaining -= count
                    }
                    output.write(byteArrayOf(10))
                }
            }
            platformMove(temporary, target)
            return fs.metadataOrNull(target)?.size ?: 0
        } catch (failure: Throwable) {
            runCatching { fs.delete(temporary, mustExist = false) }
            throw failure
        } finally { inputs.forEach { it.second.close() } }
    }

    internal fun checkedPath(value: String): Path {
        require(value.startsWith('/') && value.split('/').none { it == ".." } && '\n' !in value && '\r' !in value)
        return Path(value)
    }
    @Throws(Exception::class)
    fun snapshot(path: String): DiagnosticSnapshotFile {
        val requested = checkedPath(path)
        check(safeFile(requested)) { "Snapshot source unavailable" }
        val resolved = fs.resolve(requested)
        return DiagnosticSnapshotFile(resolved.toString(), resolved.name, fs.metadataOrNull(resolved)?.size ?: 0)
    }
    private fun safeFile(path: Path): Boolean {
        if (fs.metadataOrNull(path)?.isRegularFile != true) return false
        val parent = Path(path.toString().substringBeforeLast('/').ifEmpty { "/" })
        return fs.resolve(path) == Path(fs.resolve(parent), path.name)
    }
    private fun checkedFile(file: DiagnosticSnapshotFile): Path {
        val path = checkedPath(file.path)
        check(safeFile(path)) { "Snapshot source unavailable" }
        check((fs.metadataOrNull(path)?.size ?: 0) >= file.size) { "Snapshot source shortened" }
        return path
    }
    private fun open(file: DiagnosticSnapshotFile): Source = fs.source(checkedFile(file)).buffered()
}

internal expect fun platformReadTail(path: Path, offset: Long, length: Int): ByteArray
