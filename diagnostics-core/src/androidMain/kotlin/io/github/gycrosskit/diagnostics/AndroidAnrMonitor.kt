package io.github.gycrosskit.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 由宿主按 Debug/QA 策略启用的 ANR 采集器，由两条互补路径组成：
 *
 * 1. Android 11+ 在下次启动读取 [ApplicationExitInfo]，获得系统最终认定的真实 ANR 及 trace。
 * 2. 当前进程使用主线程看门狗，在系统杀进程前保存主线程和其他线程栈，弥补部分 ROM 不提供 trace 的情况。
 *
 * 看门狗每 1 秒探测，主线程超过 5 秒仅采样一次；恢复前不重复保存，阈值不是系统 ANR 判定。
 * context 必须提供 Application；宿主在主线程串行 start、后台串行 close，先隐私准入。
 * reportStore 由宿主拥有，log 在采集工作线程调用，必须轻量且不能依赖主线程同步等待。
 * 看门狗只向主线程投递轻量 Runnable，不做业务埋点，不改变 Activity 生命周期。
 */
class AndroidAnrMonitor(
    context: Context,
    private val reportStore: AnrReportStore,
    private val log: (AnrLogLevel, String, String) -> Unit = { _, _, _ -> },
) : java.io.Closeable, Application.ActivityLifecycleCallbacks {
    private val application = context.applicationContext as Application
    private val started = AtomicBoolean(false)

    @Volatile
    private var foregroundActivity: String = ""

    private var watchdog: Thread? = null
    private var systemImport: Thread? = null

    /** 启停由宿主串行调用；本组件不判断构建类型或隐私准入。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(this)
        importSystemAnrsAsync()
        startMainThreadWatchdog()
    }

    /** 系统 ANR 只能在进程重启后读取；文件名稳定，所以重复启动不会生成重复报告。 */
    private fun importSystemAnrsAsync() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        systemImport = Thread(
            {
                try {
                    importSystemAnrs()
                } catch (error: Throwable) {
                    logDiagnosticFailure("读取系统 ANR 历史失败", error)
                }
            },
            SYSTEM_IMPORT_THREAD_NAME,
        ).apply {
            isDaemon = true
            start()
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun importSystemAnrs() {
        val activityManager = application.getSystemService(ActivityManager::class.java) ?: return
        val exits = activityManager.getHistoricalProcessExitReasons(null, 0, MAX_EXIT_RECORDS)
        val knownReportNames = reportStore.pendingReportFiles().mapTo(mutableSetOf(), File::getName)
        var imported = 0
        exits.asSequence()
            .filter { it.reason == ApplicationExitInfo.REASON_ANR }
            .forEach { exit ->
                if (!started.get() || Thread.currentThread().isInterrupted) return
                val systemTrace = try {
                    exit.traceInputStream?.use { it.readTextLimited(MAX_SYSTEM_TRACE_CHARACTERS) }
                } catch (_: Exception) {
                    null
                }.orEmpty()
                val mainThreadStack = extractMainThreadTrace(systemTrace)
                val report = AnrReport(
                    source = AnrReportSource.SYSTEM,
                    timestampMillis = exit.timestamp,
                    processName = exit.processName.orEmpty(),
                    pid = exit.pid,
                    importance = importanceName(exit.importance),
                    pssKb = exit.pss,
                    rssKb = exit.rss,
                    description = exit.description.orEmpty().ifBlank { "Android 系统判定应用无响应" },
                    suspectedReason = analyzeAnr(mainThreadStack, systemTrace),
                    mainThreadStack = mainThreadStack,
                    systemTrace = systemTrace,
                )
                reportStore.record(report)?.let { target ->
                    if (knownReportNames.add(target.name)) imported += 1
                }
            }
        if (imported > 0) log(AnrLogLevel.INFO, LOG_TAG, "已检查并保存 $imported 条系统 ANR 历史")
    }

    /**
     * 每轮等待主线程确认一次消息；超过阈值后只采样一次，并等待主线程恢复后才开始下一轮，避免同一次
     * 卡死刷出多份重复文件。
     */
    private fun startMainThreadWatchdog() {
        val mainHandler = Handler(Looper.getMainLooper())
        watchdog = Thread(
            {
                while (started.get() && !Thread.currentThread().isInterrupted) {
                    val acknowledged = CountDownLatch(1)
                    val postedAt = SystemClock.uptimeMillis()
                    if (!mainHandler.post { acknowledged.countDown() }) return@Thread
                    try {
                        if (!acknowledged.await(ANR_THRESHOLD_MILLIS, TimeUnit.MILLISECONDS)) {
                            captureWatchdogReport(
                                blockedDurationMillis = SystemClock.uptimeMillis() - postedAt,
                            )
                            while (!acknowledged.await(RECOVERY_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                                // 同一次卡顿只保存一次，主线程恢复后再重新布置探针。
                            }
                        }
                        Thread.sleep(WATCHDOG_INTERVAL_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            },
            WATCHDOG_THREAD_NAME,
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun captureWatchdogReport(blockedDurationMillis: Long) {
        if (!started.get()) return
        try {
            captureWatchdogReportUnsafe(blockedDurationMillis)
        } catch (error: Throwable) {
            logDiagnosticFailure("主线程卡顿现场采集失败", error)
        }
    }

    private fun captureWatchdogReportUnsafe(blockedDurationMillis: Long) {
        val mainThread = Looper.getMainLooper().thread
        val mainThreadStack = formatThreadStack(
            threadName = mainThread.name,
            threadState = mainThread.state.name,
            stack = mainThread.stackTrace,
            maxFrames = MAX_MAIN_THREAD_FRAMES,
            maxCharacters = MAX_MAIN_THREAD_STACK_CHARACTERS,
        )
        val allThreadStacks = allThreadStacks(mainThread)
        val timestampMillis = System.currentTimeMillis()
        val report = AnrReport(
            source = AnrReportSource.WATCHDOG,
            timestampMillis = timestampMillis,
            processName = currentProcessName(),
            pid = Process.myPid(),
            foregroundActivity = foregroundActivity,
            importance = currentImportance(),
            pssKb = Debug.getPss(),
            cpuTimeMillis = Process.getElapsedCpuTime(),
            blockedDurationMillis = blockedDurationMillis,
            description = "主线程至少 ${blockedDurationMillis}ms 未处理看门狗消息",
            suspectedReason = analyzeAnr(mainThreadStack),
            mainThreadStack = mainThreadStack,
            allThreadStacks = allThreadStacks,
        )
        val target = reportStore.record(report)
        if (target == null) {
            log(AnrLogLevel.WARNING, LOG_TAG, "检测到主线程卡顿，但 ANR 报告写入失败")
        } else {
            log(AnrLogLevel.WARNING,
                LOG_TAG,
                "检测到主线程卡顿 ${blockedDurationMillis}ms，报告=${target.name}，" +
                    "原因=${report.suspectedReason}",
            )
        }
    }

    /** 撤回监听并等待已开始的采集；系统 trace I/O 没有期限，宿主须在后台调用。 */
    override fun close() {
        if (!started.compareAndSet(true, false)) return
        application.unregisterActivityLifecycleCallbacks(this)
        val workers = listOfNotNull(watchdog, systemImport)
        workers.forEach(Thread::interrupt)
        workers.filter { it !== Thread.currentThread() }.forEach(Thread::join)
        watchdog = null
        systemImport = null
        foregroundActivity = ""
    }

    private fun allThreadStacks(mainThread: Thread): String {
        val snapshots = ArrayList<ThreadStackSnapshot>(MAX_OTHER_THREADS)
        val candidates = arrayOfNulls<Thread>(MAX_ENUMERATED_THREADS)
        val enumerated = rootThreadGroup().enumerate(candidates, true)
        var omittedThreads = if (enumerated >= candidates.size) 1 else 0
        for (index in 0 until minOf(enumerated, candidates.size)) {
            val thread = candidates[index] ?: continue
            if (thread == mainThread || !thread.isAlive) continue
            if (snapshots.size < MAX_OTHER_THREADS) {
                snapshots += ThreadStackSnapshot(
                    name = thread.name,
                    state = thread.state.name,
                    stack = thread.stackTrace,
                )
            } else {
                omittedThreads += 1
            }
        }
        return formatThreadDump(
            snapshots = snapshots,
            omittedThreads = omittedThreads,
            maxFramesPerThread = MAX_OTHER_THREAD_FRAMES,
            maxCharactersPerThread = MAX_OTHER_THREAD_STACK_CHARACTERS,
            maxCharacters = MAX_ALL_THREAD_STACK_CHARACTERS,
        )
    }

    private fun rootThreadGroup(): ThreadGroup {
        var group = requireNotNull(Thread.currentThread().threadGroup)
        while (true) {
            group = group.parent ?: return group
        }
    }

    /** OOM 等诊断失败只输出固定短消息，不再序列化异常栈，避免错误处理继续放大内存压力。 */
    private fun logDiagnosticFailure(message: String, error: Throwable) {
        try {
            log(AnrLogLevel.WARNING,
                LOG_TAG,
                if (error is OutOfMemoryError) "$message：内存不足，已放弃本次采集" else message,
            )
        } catch (_: Throwable) {
            // 日志系统异常也不能反向中断业务进程。
        }
    }

    private fun currentImportance(): String {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return importanceName(info.importance)
    }

    private fun currentProcessName(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        application.packageName
    }

    override fun onActivityResumed(activity: Activity) {
        foregroundActivity = activity::class.java.name
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val LOG_TAG = "AnrMonitor"
        const val SYSTEM_IMPORT_THREAD_NAME = "anr-history-import"
        const val WATCHDOG_THREAD_NAME = "main-thread-watchdog"
        const val MAX_EXIT_RECORDS = 16
        const val MAX_SYSTEM_TRACE_CHARACTERS = 192 * 1024
        const val MAX_MAIN_THREAD_FRAMES = 100
        const val MAX_OTHER_THREADS = 30
        const val MAX_ENUMERATED_THREADS = 64
        const val MAX_OTHER_THREAD_FRAMES = 80
        const val MAX_MAIN_THREAD_STACK_CHARACTERS = 48 * 1024
        const val MAX_OTHER_THREAD_STACK_CHARACTERS = 8 * 1024
        const val MAX_ALL_THREAD_STACK_CHARACTERS = 128 * 1024
        const val ANR_THRESHOLD_MILLIS = 5_000L
        const val RECOVERY_POLL_MILLIS = 1_000L
        const val WATCHDOG_INTERVAL_MILLIS = 1_000L
    }
}

/** 宿主将轻量日志接入已有 Logger；组件不持有业务日志依赖。 */
enum class AnrLogLevel { INFO, WARNING }

internal data class ThreadStackSnapshot(
    val name: String,
    val state: String,
    val stack: Array<StackTraceElement>,
)

internal fun formatThreadStack(
    threadName: String,
    threadState: String,
    stack: Array<StackTraceElement>,
    maxFrames: Int,
    maxCharacters: Int,
): String {
    val output = BoundedTextBuilder(maxCharacters, STACK_TRUNCATION_MARKER)
    output.append('"')
    output.append(threadName)
    output.append('"')
    output.append(" state=")
    output.append(threadState)
    val frameCount = minOf(stack.size, maxFrames.coerceAtLeast(0))
    for (index in 0 until frameCount) {
        if (!output.append("\n    at ${stack[index]}")) break
    }
    if (stack.size > frameCount) output.markTruncated()
    return output.build()
}

internal fun formatThreadDump(
    snapshots: List<ThreadStackSnapshot>,
    omittedThreads: Int,
    maxFramesPerThread: Int,
    maxCharactersPerThread: Int,
    maxCharacters: Int,
): String {
    val output = BoundedTextBuilder(maxCharacters, THREAD_DUMP_TRUNCATION_MARKER)
    for ((index, snapshot) in snapshots.withIndex()) {
        if (index > 0 && !output.append("\n\n")) break
        val stack = formatThreadStack(
            threadName = snapshot.name,
            threadState = snapshot.state,
            stack = snapshot.stack,
            maxFrames = maxFramesPerThread,
            maxCharacters = maxCharactersPerThread,
        )
        if (!output.append(stack)) break
    }
    if (omittedThreads > 0) output.markTruncated()
    return output.build()
}

private fun InputStream.readTextLimited(maxCharacters: Int): String {
    val payloadLimit = (maxCharacters - INPUT_TRUNCATION_MARKER.length).coerceAtLeast(0)
    val output = StringBuilder(minOf(payloadLimit, 16 * 1024))
    bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(8 * 1024)
        while (output.length < payloadLimit) {
            val count = reader.read(buffer, 0, minOf(buffer.size, payloadLimit - output.length))
            if (count < 0) break
            output.append(buffer, 0, count)
        }
        if (output.length == payloadLimit && reader.read() >= 0) output.append(INPUT_TRUNCATION_MARKER)
    }
    return output.toString()
}

/** StringBuilder 的容量和写入都受控，截断时始终保留原因标记。 */
private class BoundedTextBuilder(
    maxCharacters: Int,
    private val truncationMarker: String,
) {
    private val payloadLimit = (maxCharacters - truncationMarker.length).coerceAtLeast(0)
    private val output = StringBuilder(minOf(payloadLimit, 4 * 1024))
    private var truncated = false

    fun append(value: Char): BoundedTextBuilder {
        if (output.length < payloadLimit) output.append(value) else truncated = true
        return this
    }

    fun append(value: String): Boolean {
        val remaining = payloadLimit - output.length
        if (remaining <= 0) {
            truncated = true
            return false
        }
        val count = minOf(value.length, remaining)
        output.append(value, 0, count)
        if (count < value.length) truncated = true
        return count == value.length
    }

    fun markTruncated() {
        truncated = true
    }

    fun build(): String {
        if (truncated) output.append(truncationMarker)
        return output.toString()
    }
}

private fun importanceName(value: Int): String = when (value) {
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
    else -> value.toString()
}

private const val STACK_TRUNCATION_MARKER = "\n    ... stack frames truncated ..."
private const val THREAD_DUMP_TRUNCATION_MARKER = "\n\n... thread dump truncated ..."
private const val INPUT_TRUNCATION_MARKER = "\n... system trace truncated ..."
