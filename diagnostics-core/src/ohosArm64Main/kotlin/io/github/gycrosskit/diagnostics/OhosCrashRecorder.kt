package io.github.gycrosskit.diagnostics

import cnames.structs.HiAppEvent_Watcher
import kotlinx.cinterop.*
import platform.PerformanceAnalysisKit.HiAppEvent.*

/**
 * API 12+ 系统延迟 APP_CRASH / APP_FREEZE 原文，隐私准入后显式创建，不导入 external_log 附件。
 * 宿主串行创建/close，store 由宿主拥有；回调在线程不确定的系统入口同步落盘，成功后才通知。
 * 进程内只有一个 watcher，成功安装后 close 不允许重装，避免无身份的迟回调污染新实例。
 */
@OptIn(ExperimentalForeignApi::class)
class OhosCrashRecorder(store: DiagnosticStore, onReportStored: ((ReportKind) -> Unit)? = null) {
    private var watcher: CPointer<HiAppEvent_Watcher>? = null
    private val reports = ReportRecorder(store, onReportStored)

    init {
        watcherGate.locked {
            check(!watcherReserved) { "本进程已安装过 OhosCrashRecorder；关闭后不能重新安装" }
            watcherReserved = true
            activeRecorder = reports
        }
        val created = OH_HiAppEvent_CreateWatcher("gycrosskit_diagnostics")
        try {
            checkNotNull(created) { "HiAppEvent 创建失败" }
            memScoped {
                val names = allocArray<CPointerVar<ByteVar>>(ohosSystemEvents.size)
                ohosSystemEvents.forEachIndexed { index, name -> names[index] = name.cstr.ptr }
                check(OH_HiAppEvent_SetAppEventFilter(created, "OS", 1u, names, ohosSystemEvents.size) == 0)
            }
            check(OH_HiAppEvent_SetWatcherOnReceive(created, staticCFunction { _, groups, count ->
                try {
                    // 在 watcher 生命周期锁内复制 C 数据；通知在锁外执行，close 后已复制事件也不会复活。
                    val received = watcherGate.locked {
                        val receiver = activeRecorder ?: return@locked null
                        val payloads = mutableListOf<Pair<ReportKind, String>>()
                        for (groupIndex in 0 until count.toInt()) {
                            val group = groups?.get(groupIndex) ?: continue
                            for (eventIndex in 0 until group.infoLen.toInt()) {
                                val event = group.appEventInfos?.get(eventIndex) ?: continue
                                val kind = ohosSystemReportKind(event.name?.toKString()) ?: continue
                                event.params?.toKString()?.let { payloads += kind to it }
                            }
                        }
                        receiver to payloads
                    }
                    received?.let { (receiver, payloads) ->
                        payloads.forEach { (kind, text) -> receiver.record(kind, text) }
                    }
                } catch (_: Throwable) {
                    // 系统回调不能把文件 I/O 或宿主通知异常传播到 C ABI。
                }
                Unit
            }) == 0)
            check(OH_HiAppEvent_AddWatcher(created) == 0) { "HiAppEvent 安装失败" }
            watcher = created
        } catch (failure: Throwable) {
            watcherGate.locked { activeRecorder = null }
            reports.close()
            created?.let { OH_HiAppEvent_DestroyWatcher(it) }
            watcherGate.locked { watcherReserved = false }
            throw failure
        }
    }

    /** 在关闭 store 前调用；RemoveWatcher 失败保留句柄，调用方可以重试。 */
    fun close() {
        reports.close()
        val current = watcherGate.locked {
            val existing = watcher ?: return@locked null
            activeRecorder = null
            watcher = null
            existing
        } ?: return
        if (OH_HiAppEvent_RemoveWatcher(current) != 0) {
            watcherGate.locked { watcher = current }
            error("HiAppEvent 移除失败")
        }
        OH_HiAppEvent_DestroyWatcher(current)
        // ponytail: API 12 回调无 watcher 身份，卸载后仍可能晚到；支持 userData 后再允许进程内重装。
        watcherGate.locked { watcher = null }
    }
}

private val watcherGate = StoreLock()
private var activeRecorder: ReportRecorder? = null
private var watcherReserved = false

private val ohosSystemEvents = listOf("APP_CRASH", "APP_FREEZE")

internal fun ohosSystemReportKind(name: String?): ReportKind? = when (name) {
    "APP_CRASH" -> ReportKind.CRASH
    "APP_FREEZE" -> ReportKind.HANG
    else -> null
}
