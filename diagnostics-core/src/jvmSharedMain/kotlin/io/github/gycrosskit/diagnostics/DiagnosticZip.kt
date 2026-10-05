package io.github.gycrosskit.diagnostics

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * @property archivePath ZIP 内相对路径，非空且不得含父目录跳转；active/frozen 同名应使用不同路径。
 * @property file 授权私有文件的长度快照，后续追加不导出。
 */
data class DiagnosticZipFile(val archivePath: String, val file: DiagnosticSnapshotFile)

/**
 * Android/JVM 同步流式 ZIP，不冻结/确认源批次；失败删除临时产物且保留原件。
 * destination 是不存在的私有绝对文件路径，所有归档路径必须唯一；textEntries 由宿主先脱敏。
 * 输入 reader 在所有分支释放，返回成功归档路径；宿主负责分享权限和最终归档删除。
 */
fun exportDiagnosticZip(destination: String, files: List<DiagnosticZipFile>, textEntries: Map<String, String> = emptyMap()): String {
    val paths = files.map { it.archivePath } + textEntries.keys
    require(paths.distinct().size == paths.size)
    require(paths.all { it.isNotBlank() && !it.startsWith('/') && it.split('/').none { segment -> segment == ".." } })
    val target = File(DiagnosticFiles.checkedPath(destination).toString())
    require(!target.exists() && files.none { File(it.file.path).canonicalFile == target.canonicalFile })
    val temporary = File("$destination.tmp")
    require(!temporary.exists()) { "Temporary destination already exists" }
    val inputs = mutableListOf<Pair<DiagnosticZipFile, DiagnosticFileReader>>()
    try {
        files.forEach { inputs += it to DiagnosticFiles.openSnapshot(it.file) }
        ZipOutputStream(temporary.outputStream().buffered()).use { output ->
            for ((path, text) in textEntries) { output.putNextEntry(ZipEntry(path)); output.write(text.toByteArray()); output.closeEntry() }
            for ((entry, input) in inputs) {
                output.putNextEntry(ZipEntry(entry.archivePath))
                while (true) { val bytes = input.read(); if (bytes.isEmpty()) break; output.write(bytes) }
                output.closeEntry()
            }
        }
        check(temporary.renameTo(target))
        return target.absolutePath
    } catch (failure: Throwable) { temporary.delete(); throw failure }
    finally { inputs.forEach { it.second.close() } }
}
