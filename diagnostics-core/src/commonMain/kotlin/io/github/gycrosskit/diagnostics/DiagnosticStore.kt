package io.github.gycrosskit.diagnostics

import kotlinx.io.buffered
import kotlinx.io.Source
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 私有滚动日志和稳定诊断批次。一个目录只允许一个进程中的一个实例持有。 */
@OptIn(ExperimentalTime::class)
class DiagnosticStore @Throws(Exception::class) constructor(rootDirectory: String, val limits: DiagnosticLimits = DiagnosticLimits(), legacySources: List<LegacyDiagnosticSource> = emptyList()) {
    private val lock = StoreLock()
    private val fs = SystemFileSystem
    private val root: Path
    private val legacySources = legacySources.map { source ->
        val path = DiagnosticFiles.checkedPath(source.directory)
        source.copy(directory = if (fs.exists(path)) fs.resolve(path).toString() else path.toString())
    }
    private val pending: Path
    private val owner = Any()
    private var closed = false

    init {
        requireAbsolute(rootDirectory)
        legacySources.forEach { requireAbsolute(it.directory); require(it.prefixes.isNotEmpty() && it.prefixes.all(String::isNotEmpty)) }
        fs.createDirectories(Path(rootDirectory))
        root = fs.resolve(Path(rootDirectory))
        pending = Path(root, "pending")
        fs.createDirectories(pending)
        check(fs.resolve(pending) == pending) { "诊断目录不能指向符号链接" }
    }

    /** 同步串行文件 I/O；宿主应在自己的有界后台队列调用。失败向调用方报告。 */
    @Throws(Exception::class)
    fun append(line: String) = lock.locked {
        checkOpen()
        val bytes = boundedUtf8(line, limits.maxLogBytes - 1) + byteArrayOf(10)
        val active = logPath(0)
        if (size(active) + bytes.size > limits.maxLogBytes) rotate()
        fs.sink(active, append = true).buffered().use { it.write(bytes) }
    }

    /** 原子保存宿主或系统提供的诊断原文。报告已满时返回 false，保留已有待导出报告。 */
    @Throws(Exception::class)
    fun recordReport(kind: ReportKind, text: String): Boolean = lock.locked {
        checkOpen()
        require(text.isNotEmpty()) { "诊断报告不能为空" }
        if ((files(root) + files(pending)).count { isReport(it.name) } >= limits.maxReports) return@locked false
        val target = Path(root, "${kind.name.lowercase()}_${newId()}.txt")
        val temporary = Path(root, "${target.name}.tmp")
        try {
            fs.sink(temporary).buffered().use { it.write(boundedUtf8(text, limits.maxReportBytes)) }
            platformMove(temporary, target)
        } catch (failure: Throwable) {
            runCatching { fs.delete(temporary, mustExist = false) }
            throw failure
        }
        true
    }

    /** 复用未确认批次；活动日志由 rename 冻结，后续写入独立文件，不复制崩溃原件。 */
    @Throws(Exception::class)
    fun prepareBatch(): DiagnosticBatch = lock.locked {
        checkOpen()
        var frozen = files(pending)
        if (frozen.isEmpty() && legacySources.isNotEmpty()) {
            importLegacyBatch()
            frozen = files(pending)
        }
        if (frozen.isEmpty()) {
            val candidates = files(root).sortedBy { !isReport(it.name) }
            val selected = mutableListOf<Path>()
            var total = 0L
            for (file in candidates) {
                val length = size(file)
                if (selected.size < limits.maxBatchFiles && length <= limits.maxBatchBytes - total) {
                    selected += file
                    total += length
                }
            }
            check(selected.isNotEmpty() || candidates.isEmpty()) { "诊断文件超过批次容量，保留源文件" }
            if (selected.isNotEmpty()) {
                writeId(newId())
                selected.forEach { platformMove(it, Path(pending, it.name)) }
            }
            frozen = files(pending)
        }
        check(frozen.size <= limits.maxBatchFiles && frozen.sumOf { size(it) } <= limits.maxBatchBytes) {
            "已有批次超过当前容量配置，保留文件"
        }
        DiagnosticBatch(if (frozen.isEmpty()) "empty" else readId(), frozen.map {
            DiagnosticFile(it.name, size(it))
        }, owner)
    }

    /** 流式生成标准 POSIX ustar 单文件归档；全部成功后才公开 .tar 文件。失败保留 pending。 */
    @Throws(Exception::class)
    fun exportBatch(batch: DiagnosticBatch, destinationDirectory: String): DiagnosticExport = lock.locked {
        checkOpen()
        validate(batch)
        require(batch.files.isNotEmpty()) { "空批次无需导出" }
        requireAbsolute(destinationDirectory)
        val requested = Path(destinationDirectory).toString()
        require(requested != root.toString() && !requested.startsWith("$root/")) { "导出目录必须在诊断目录之外" }
        fs.createDirectories(Path(destinationDirectory))
        val parent = fs.resolve(Path(destinationDirectory))
        require(parent != root && !parent.toString().startsWith("$root/")) { "导出目录必须在诊断目录之外" }
        val destination = Path(parent, "diagnostics_${batch.id}_${newId()}.tar")
        val temporary = Path(parent, "${destination.name}.tmp")
        try {
            fs.sink(temporary).buffered().use { output ->
                batch.files.forEach { file ->
                    output.write(tarHeader(file))
                    fs.source(Path(pending, file.name)).buffered().use { input ->
                        var remaining = file.size
                        while (remaining > 0) {
                            val count = minOf(remaining, 16 * 1024L).toInt()
                            output.write(input.readByteArray(count))
                            remaining -= count
                        }
                        check(input.exhausted()) { "诊断文件在导出时发生变化，保留源文件" }
                    }
                    output.write(ByteArray(tarPadding(file.size)))
                }
                output.write(ByteArray(1024))
            }
            platformMove(temporary, destination)
        } catch (failure: Throwable) {
            runCatching { fs.delete(temporary, mustExist = false) }
            throw failure
        }
        DiagnosticExport(destination.toString(), batch, owner)
    }

    /** 宿主完成分享/上传后明确确认。逐字节核对 TAR，只删除原批次，保留后来产生的日志。 */
    @Throws(Exception::class)
    fun deleteExported(export: DiagnosticExport) = lock.locked {
        checkOpen()
        check(export.owner === owner) { "导出回执来自其他实例" }
        validate(export.batch)
        check(matchesArchive(export)) { "归档副本缺失或已变化，保留原批次" }
        acknowledgeLocked(export.batch)
    }

    /** 逐文件读取冻结批次，不公开私有路径。调用方在上传结束或失败后必须关闭 reader。 */
    @Throws(Exception::class)
    fun openFile(batch: DiagnosticBatch, fileId: String): DiagnosticFileReader = lock.locked {
        checkOpen()
        validate(batch)
        val file = batch.files.singleOrNull { it.id == fileId }
            ?: throw IllegalArgumentException("文件不属于当前批次")
        DiagnosticFileReader(fs.source(Path(pending, file.name)).buffered(), file.size)
    }

    /** 仅在整个批次全部上传成功且 reader 已关闭后调用；失败或部分成功不要确认。 */
    @Throws(Exception::class)
    fun acknowledgeBatch(batch: DiagnosticBatch) = lock.locked {
        checkOpen()
        validate(batch)
        acknowledgeLocked(batch)
    }

    @Throws(Exception::class)
    fun pendingReportCount(): Int = lock.locked {
        checkOpen()
        (files(root) + files(pending)).count { isReport(it.name) }
    }

    /** 不冻结、不切卷；同名活动/冻结文件用完整路径区分。 */
    @Throws(Exception::class)
    fun currentFiles(): List<DiagnosticSnapshotFile> = lock.locked {
        checkOpen()
        (files(root) + files(pending)).map { DiagnosticSnapshotFile(it.toString(), it.name, size(it)) } +
            legacySources.flatMap { DiagnosticFiles.list(it.directory, it.prefixes) }
    }

    @Throws(Exception::class)
    fun openSnapshot(file: DiagnosticSnapshotFile): DiagnosticFileReader = lock.locked {
        checkOpen()
        check((files(root) + files(pending)).any { it.toString() == file.path && size(it) >= file.size } ||
            legacySources.any { DiagnosticFiles.list(it.directory, it.prefixes).any { item -> item.path == file.path && item.size >= file.size } })
        DiagnosticFiles.openSnapshot(file)
    }

    @Throws(Exception::class)
    fun readTail(file: DiagnosticSnapshotFile, maxBytes: Int, dropPartialFirstLine: Boolean = false): ByteArray = lock.locked {
        checkOpen()
        check((files(root) + files(pending)).any { it.toString() == file.path && size(it) >= file.size } ||
            legacySources.any { DiagnosticFiles.list(it.directory, it.prefixes).any { item -> item.path == file.path && item.size >= file.size } })
        DiagnosticFiles.readTail(file, maxBytes, dropPartialFirstLine)
    }

    private fun acknowledgeLocked(batch: DiagnosticBatch) {
        val origins = Path(pending, "legacy-origins")
        if (fs.exists(origins)) {
            fs.source(origins).buffered().use { it.readString() }.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
                val name = line.substringBefore('\t')
                check(name.startsWith("legacy_") && '/' !in name && name != "..") { "Invalid legacy manifest" }
                if (batch.files.none { it.name == name }) return@forEach
                val original = line.substringAfter('\t', "")
                // 来源路径必须仍属于本次宿主明确配置，不能信任旧目录里的任意 manifest。
                check(legacySources.any { source -> original.startsWith("${source.directory}/") &&
                    original.removePrefix("${source.directory}/").let { '/' !in it && source.prefixes.any(it::startsWith) } })
                if (DiagnosticFiles.sameContents(original, Path(pending, name).toString())) fs.delete(Path(original))
            }
        }
        batch.files.forEach { fs.delete(Path(pending, it.name)) }
        fs.delete(origins, mustExist = false)
        fs.delete(Path(pending, "batch-id"), mustExist = false)
        legacySources.filter { it.frozen }.forEach { source ->
            if (DiagnosticFiles.list(source.directory, source.prefixes).isEmpty())
                fs.delete(Path(source.directory, "batch-id"), mustExist = false)
        }
    }

    /** 仅迁移宿主明确输入的旧目录；复制成功后原子公开到现有 pending，失败和取消不删除旧文件。 */
    private fun importLegacyBatch() {
        val frozen = legacySources.filter { it.frozen }.firstOrNull { DiagnosticFiles.list(it.directory, it.prefixes).isNotEmpty() }
        val sources = if (frozen != null) listOf(frozen) else legacySources.filter { !it.frozen }
        val candidates = sources.flatMap { source -> DiagnosticFiles.list(source.directory, source.prefixes).map { source to it } }
        if (candidates.isEmpty()) return
        val selected = mutableListOf<Pair<LegacyDiagnosticSource, DiagnosticSnapshotFile>>()
        var total = 0L
        for (candidate in candidates) if (selected.size < limits.maxBatchFiles && candidate.second.size <= limits.maxBatchBytes - total) {
            selected += candidate; total += candidate.second.size
        }
        check(selected.isNotEmpty() && (frozen == null || selected.size == candidates.size)) { "Legacy batch exceeds capacity; sources preserved" }
        val staging = Path(root, "legacy-import")
        if (fs.exists(staging)) {
            check(fs.resolve(staging) == staging)
            fs.list(staging).forEach { fs.delete(it) }; fs.delete(staging)
        }
        fs.createDirectories(staging)
        try {
            val origins = StringBuilder()
            selected.forEachIndexed { index, (_, file) ->
                val name = "legacy_${index}_${file.name}"
                require(name.length < 100 && '\t' !in file.path)
                DiagnosticFiles.openSnapshot(file).let { reader ->
                    try { fs.sink(Path(staging, name)).buffered().use { output ->
                        while (true) { val bytes = reader.read(); if (bytes.isEmpty()) break; output.write(bytes) }
                    } } finally { reader.close() }
                }
                origins.append(name).append('\t').append(file.path).append('\n')
            }
            fs.sink(Path(staging, "legacy-origins")).buffered().use { it.writeString(origins.toString()) }
            val historicalId = frozen?.let { source ->
                val marker = Path(source.directory, "batch-id")
                if (fs.exists(marker) && size(marker) in 1..1024 && fs.resolve(marker) == marker)
                    fs.source(marker).buffered().use { it.readString() }.takeIf { it.isNotBlank() && it.matches(Regex("[a-zA-Z0-9_-]+")) }
                else null
            }
            fs.sink(Path(staging, "batch-id")).buffered().use { it.writeString(historicalId ?: newId()) }
            // pending 尚无文件；只移除组件自己空目录和旧 marker，绝不触碰旧目录。
            fs.delete(Path(pending, "batch-id"), mustExist = false)
            fs.delete(Path(pending, "legacy-origins"), mustExist = false)
            fs.delete(pending)
            platformMove(staging, pending)
        } catch (failure: Throwable) {
            runCatching { if (fs.exists(staging)) { fs.list(staging).forEach { fs.delete(it) }; fs.delete(staging) } }
            if (!fs.exists(pending)) fs.createDirectories(pending)
            throw failure
        }
    }

    /** 没有后台队列或打开文件；close 后拒绝调用。停止平台采集器后再关闭实例。 */
    fun close() = lock.locked { closed = true }

    private fun validate(batch: DiagnosticBatch) {
        check(batch.owner === owner) { "批次来自其他实例" }
        val current = files(pending).map { DiagnosticFile(it.name, size(it)) }
        check(batch.files == current && (current.isEmpty() || batch.id == readId())) {
            "诊断批次已变化，保留源文件"
        }
    }

    private fun matchesArchive(export: DiagnosticExport): Boolean {
        val archive = Path(export.path)
        if (!fs.exists(archive) || fs.resolve(archive) != archive) return false
        return fs.source(archive).buffered().use { input ->
            export.batch.files.forEach { file ->
                if (!input.readByteArray(512).contentEquals(tarHeader(file))) return false
                fs.source(Path(pending, file.name)).buffered().use { source ->
                    var remaining = file.size
                    while (remaining > 0) {
                        val count = minOf(remaining, 16 * 1024L).toInt()
                        if (!input.readByteArray(count).contentEquals(source.readByteArray(count))) return false
                        remaining -= count
                    }
                    if (!source.exhausted()) return false
                }
                if (!input.readByteArray(tarPadding(file.size)).all { it == 0.toByte() }) return false
            }
            input.readByteArray(1024).all { it == 0.toByte() } && input.exhausted()
        }
    }

    private fun rotate() {
        fs.delete(logPath(limits.maxLogFiles - 1), mustExist = false)
        for (index in limits.maxLogFiles - 2 downTo 0) {
            if (fs.exists(logPath(index))) platformMove(logPath(index), logPath(index + 1))
        }
    }

    private fun files(directory: Path): List<Path> = fs.list(directory).filter {
        val validName = it.name.matches(Regex("log_[0-9]+\\.txt|(?:crash|hang|system)_[a-f0-9]+\\.txt"))
        (validName || (it.name.startsWith("legacy_") && directory == pending)) && fs.metadataOrNull(it)?.isRegularFile == true && size(it) > 0 && fs.resolve(it) == it
    }.sortedBy { it.name }
    private fun logPath(index: Int) = Path(root, "log_$index.txt")
    private fun size(path: Path): Long = fs.metadataOrNull(path)?.size ?: 0
    private fun checkOpen() = check(!closed) { "DiagnosticStore 已关闭" }
    private fun readId() = fs.source(Path(pending, "batch-id")).buffered().use { it.readString() }
    private fun writeId(id: String) {
        val temporary = Path(pending, "batch-id.tmp")
        fs.sink(temporary).buffered().use { it.writeString(id) }
        platformMove(temporary, Path(pending, "batch-id"))
    }
    private fun newId() = Clock.System.now().toEpochMilliseconds().toString(16) + Random.nextLong().toULong().toString(16)
}

/** maxLogFiles 包括活动卷。pending 日志不参与滚动淘汰，最多额外占一批容量。 */
data class DiagnosticLimits @Throws(IllegalArgumentException::class) constructor(
    val maxLogBytes: Int = 5 * 1024 * 1024,
    val maxLogFiles: Int = 5,
    val maxReportBytes: Int = 512 * 1024,
    val maxReports: Int = 5,
    val maxBatchBytes: Long = 60L * 1024 * 1024,
    val maxBatchFiles: Int = 20,
) {
    init {
        require(maxLogBytes in 64..16 * 1024 * 1024)
        require(maxReportBytes in 64..16 * 1024 * 1024)
        require(maxLogFiles in 1..100 && maxReports in 1..100)
        require(maxBatchBytes > 0 && maxBatchFiles in 1..200)
    }
}

enum class ReportKind { CRASH, HANG, SYSTEM }
data class DiagnosticFile(val name: String, val size: Long) {
    /** 与批次 id 共同组成稳定标识；不含路径。 */
    val id: String get() = name
}
class DiagnosticBatch internal constructor(val id: String, files: List<DiagnosticFile>, internal val owner: Any) {
    val files: List<DiagnosticFile> = files.toList()
    val totalBytes: Long get() = files.sumOf { it.size }
}
class DiagnosticExport internal constructor(val path: String, val batch: DiagnosticBatch, internal val owner: Any)

/** 有界流式 reader；不依赖 TAR 格式，同一实例的 read/close 串行。 */
class DiagnosticFileReader internal constructor(private val source: Source, private var remaining: Long, private val exactLength: Boolean = true) {
    private val lock = StoreLock()
    private var closed = false

    @Throws(Exception::class)
    fun read(maxBytes: Int = 16 * 1024): ByteArray = lock.locked {
        check(!closed) { "reader 已关闭" }
        require(maxBytes in 1..64 * 1024) { "单次读取必须在 1..65536 字节" }
        val count = minOf(remaining, maxBytes.toLong()).toInt()
        val bytes = source.readByteArray(count)
        remaining -= count
        if (remaining == 0L && exactLength) check(source.exhausted()) { "冻结文件尺寸已变化，保留批次" }
        bytes
    }

    @Throws(Exception::class)
    fun close() = lock.locked {
        if (!closed) {
            closed = true
            source.close()
        }
    }
}

private fun isReport(name: String) = if (name.startsWith("legacy_"))
    name.substringAfter('_').substringAfter('_').let { it.startsWith("crash_") || it.startsWith("hang_") || it.startsWith("system_") || it.startsWith("anr_") }
else !name.startsWith("log_")
private fun requireAbsolute(path: String) {
    require(path.startsWith('/') && path.split('/').none { it == ".." }) { "需要无父目录跳转的绝对路径" }
}

/** 在 UTF-8 字符边界截断，超长输入最多分配 maxBytes 个 UTF-16 字符的编码。 */
internal fun boundedUtf8(text: String, maxBytes: Int): ByteArray {
    val bytes = text.take(maxBytes).encodeToByteArray()
    if (bytes.size <= maxBytes && text.length <= maxBytes) return bytes
    val marker = "\n[truncated]\n".encodeToByteArray()
    var end = minOf(bytes.size, maxBytes - marker.size)
    while (end > 0 && end < bytes.size && (bytes[end].toInt() and 0xc0) == 0x80) end--
    return bytes.copyOf(end) + marker
}

internal expect class StoreLock(recursive: Boolean = false) {
    fun <T> locked(block: () -> T): T
}

/** 自有文件名均为短 ASCII，不需要 GNU/PAX 扩展；固定 mtime 避免采集额外设备信息。 */
private fun tarHeader(file: DiagnosticFile): ByteArray {
    val header = ByteArray(512)
    fun field(offset: Int, value: String) { value.encodeToByteArray().copyInto(header, offset) }
    fun octal(offset: Int, length: Int, value: Long) {
        val encoded = value.toString(8).padStart(length - 1, '0')
        require(encoded.length < length) { "TAR 字段超过 ustar 容量" }
        field(offset, encoded)
    }
    require(file.name.length < 100 && '/' !in file.name)
    field(0, file.name)
    octal(100, 8, 384)
    octal(108, 8, 0)
    octal(116, 8, 0)
    octal(124, 12, file.size)
    octal(136, 12, 0)
    for (index in 148..155) header[index] = 32
    header[156] = '0'.code.toByte()
    field(257, "ustar")
    field(263, "00")
    val checksum = header.sumOf { it.toInt() and 0xff }
    field(148, checksum.toString(8).padStart(6, '0'))
    header[154] = 0
    header[155] = 32
    return header
}

private fun tarPadding(size: Long): Int = ((512 - size % 512) % 512).toInt()

/** Android 24/25 没有 NIO Files；保留同目录 rename 的原子性。 */
internal expect fun platformMove(source: Path, destination: Path)
