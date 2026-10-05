package io.github.gycrosskit.diagnostics

import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/**
 * 某一时刻的文件长度快照；不冻结源文件，不携带账号、设备或业务 metadata。
 * @property path 私有文件的绝对路径，只供受控 I/O 使用，勿写入公开日志。
 * @property name 文件名，不含目录，用于展示/归档。
 * @property size 捕获时的字节数，须非负；后续追加不进入本快照，缩短会导致读取失败。
 */
data class DiagnosticSnapshotFile(val path: String, val name: String, val size: Long)
/**
 * 宿主明确授权迁入的历史目录，不扫描其他目录。
 * @property directory 私有绝对路径，不得含父目录跳转或换行。
 * @property prefixes 允许迁入的非空文件名前缀；临时文件和符号链接不读取。
 * @property frozen true 表示整个旧批次须一次迁入；false 允许按容量选择活动文件。
 */
data class LegacyDiagnosticSource(val directory: String, val prefixes: List<String>, val frozen: Boolean = false)

/** 通用只读文件机制，不持有日志队列或批次 owner。 */
object DiagnosticFiles {
    private val fs get() = SystemFileSystem
    /** 同步列出匹配前缀的非空常规文件快照，按名称排序；不存在目录返回空列表。 */
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

    /**
     * 同步 seek 到快照尾部，maxBytes 为 1..1 MiB；不分配整个文件。
     * dropPartialFirstLine=true 时丢弃截断首行，避免 UTF-8 半字符进入摘要；否则返回原始字节。
     */
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

    /** 同步打开快照长度范围，不读取后续追加；调用方必须在成功/失败后关闭 reader。 */
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
    /** 同步捕获安全常规文件的当前长度；拒绝符号链接文件和不可用路径，不持有文件句柄。 */
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
        require(file.size >= 0) { "Snapshot size must be non-negative" }
        val path = checkedPath(file.path)
        check(safeFile(path)) { "Snapshot source unavailable" }
        check((fs.metadataOrNull(path)?.size ?: 0) >= file.size) { "Snapshot source shortened" }
        return path
    }
    private fun open(file: DiagnosticSnapshotFile): Source = fs.source(checkedFile(file)).buffered()
}

internal expect fun platformReadTail(path: Path, offset: Long, length: Int): ByteArray
