package io.github.gycrosskit.diagnostics

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

/** 后台同步创建 Library 专属 Store 并排除系统备份；不安装采集器，宿主拥有并负责 close。 */
@OptIn(ExperimentalForeignApi::class)
@Throws(Exception::class)
fun iosDiagnosticStore(limits: DiagnosticLimits = DiagnosticLimits(), legacySources: List<LegacyDiagnosticSource> = emptyList()): DiagnosticStore {
    val library = NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true).first().toString()
    val directory = "$library/GYCrossKitDiagnostics"
    val store = DiagnosticStore(directory, limits, legacySources)
    val url = checkNotNull(NSURL.fileURLWithPath(directory))
    check(url.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)) { "无法排除诊断目录备份" }
    return store
}
