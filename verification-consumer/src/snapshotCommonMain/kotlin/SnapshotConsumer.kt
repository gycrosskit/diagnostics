package consumer

import io.github.gycrosskit.diagnostics.DiagnosticFiles
import io.github.gycrosskit.diagnostics.DiagnosticStore

/** Native Framework/共享库同样编译并链接 rc.12 的公开快照入口。 */
fun DiagnosticStore.exportPublishedSnapshot(destination: String): Long = openCurrentSnapshot().use {
    DiagnosticFiles.exportTextSnapshot(it, destination)
}
