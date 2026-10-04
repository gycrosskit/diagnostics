package io.github.gycrosskit.diagnostics

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

/** 默认 Library 私有目录并排除系统备份；不安装异常处理器或 MetricKit subscriber。 */
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
