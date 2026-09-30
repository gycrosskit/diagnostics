package io.github.gycrosskit.diagnostics

import cnames.structs.HiAppEvent_Watcher
import kotlinx.cinterop.*
import platform.PerformanceAnalysisKit.HiAppEvent.*

/** API 12+ 的系统延迟 APP_CRASH 事件原文。仅显式创建后采集，不导入 external_log 附件。 */
@OptIn(ExperimentalForeignApi::class)
class OhosCrashRecorder(store: DiagnosticStore) {
    private var watcher: CPointer<HiAppEvent_Watcher>? = null

    init {
        watcherGate.locked {
            check(!watcherReserved) { "进程已有 OhosCrashRecorder" }
            watcherReserved = true
            activeStore = store
        }
        val created = OH_HiAppEvent_CreateWatcher("gycrosskit_diagnostics")
        try {
            checkNotNull(created) { "HiAppEvent 创建失败" }
            memScoped {
                val names = allocArray<CPointerVar<ByteVar>>(1)
                names[0] = "APP_CRASH".cstr.ptr
                check(OH_HiAppEvent_SetAppEventFilter(created, "OS", 1u, names, 1) == 0)
            }
            check(OH_HiAppEvent_SetWatcherOnReceive(created, staticCFunction { _, groups, count ->
                // 与 close 串行，释放 watcher 前等待已经进入的存储回调完成。
                watcherGate.locked {
                    try {
                        val receiver = activeStore
                        if (receiver != null) {
                            for (groupIndex in 0 until count.toInt()) {
                                val group = groups?.get(groupIndex) ?: continue
                                for (eventIndex in 0 until group.infoLen.toInt()) {
                                    val event = group.appEventInfos?.get(eventIndex) ?: continue
                                    if (event.name?.toKString() == "APP_CRASH") {
                                        event.params?.toKString()?.let { receiver.recordReport(ReportKind.CRASH, it) }
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // 系统回调不能把文件 I/O 异常传播到 C ABI。
                    }
                }
                Unit
            }) == 0)
            check(OH_HiAppEvent_AddWatcher(created) == 0) { "HiAppEvent 安装失败" }
            watcher = created
        } catch (failure: Throwable) {
            watcherGate.locked { activeStore = null }
            created?.let { OH_HiAppEvent_DestroyWatcher(it) }
            watcherGate.locked { watcherReserved = false }
            throw failure
        }
    }

    /** 在关闭 store 前调用；RemoveWatcher 失败保留句柄，调用方可以重试。 */
    fun close() {
        val current = watcherGate.locked {
            val existing = watcher ?: return@locked null
            activeStore = null
            watcher = null
            existing
        } ?: return
        if (OH_HiAppEvent_RemoveWatcher(current) != 0) {
            watcherGate.locked { watcher = current }
            error("HiAppEvent 移除失败")
        }
        OH_HiAppEvent_DestroyWatcher(current)
        watcherGate.locked { watcher = null; watcherReserved = false }
    }
}

private val watcherGate = StoreLock()
private var activeStore: DiagnosticStore? = null
private var watcherReserved = false
