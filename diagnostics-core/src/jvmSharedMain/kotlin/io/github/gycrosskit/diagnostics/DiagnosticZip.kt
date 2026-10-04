package io.github.gycrosskit.diagnostics

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 宿主提供 archivePath，active/frozen 同名使用不同路径，不推断业务文件分类。 */
data class DiagnosticZipFile(val archivePath: String, val file: DiagnosticSnapshotFile)

/** Android/JVM 流式 ZIP；不冻结、不确认源批次，失败不公开半成品。 */
fun exportDiagnosticZip(destination: String, files: List<DiagnosticZipFile>, textEntries: Map<String, String> = emptyMap()): String {
    val paths = files.map { it.archivePath } + textEntries.keys
    require(paths.distinct().size == paths.size)
    require(paths.all { it.isNotBlank() && !it.startsWith('/') && it.split('/').none { segment -> segment == ".." } })
    val target = File(DiagnosticFiles.checkedPath(destination).toString())
    require(!target.exists() && files.none { File(it.file.path).canonicalFile == target.canonicalFile })
    val temporary = File("$destination.tmp")
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
