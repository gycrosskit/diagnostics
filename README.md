# GY CrossKit Diagnostics

跨项目的私有滚动日志、诊断报告、稳定批次逐文件读取和可重试 TAR 导出。**没有网络上传、账号、用户信息采集、用户授权判断、脱敏或模拟崩溃入口。** 宿主决定是否启用、写入什么内容、如何脱敏和分享/上传；组件不会替宿主作隐私决策。

远程发布候选坐标为 `com.github.gycrosskit.diagnostics:diagnostics-core:0.1.0`，使用 JitPack。
发布前可在独立消费工程以 `-PdiagnosticsMavenRepo="$PWD/build/maven"` 检查 staging 产物；
默认消费工程只查询 JitPack，不使用源码替换或 `mavenLocal`。远程发布与可用性以 [VALIDATION.md](VALIDATION.md) 的实际结果为准。


## 实际能力和平台

| 平台 | 本地日志/报告/TAR | 可选系统采集 | 最低版本与边界 |
| --- | --- | --- | --- |
| Android | `DiagnosticStore`，`androidDiagnosticStore(Context)` 使用 noBackupFilesDir | 显式创建 `AndroidCrashRecorder` 记录 JVM 未捕获异常，调用之前的系统 handler | minSdk 24；不捕获 native signal、ANR、进程被杀 |
| iOS | `DiagnosticStore`，`iosDiagnosticStore()` 使用 Library 并排除备份 | 独立 Swift 文件 `ios-support/GYMetricKitRecorder.swift` 订阅系统延迟诊断 JSON，宿主转给 recordReport | Swift 支持 iOS 14+；不安装 NSException/signal handler，不承诺 crash/hang 即时或必达 |
| OpenHarmony | 原生 Kotlin/Native 文件系统与 pthread 锁，宿主传 filesDir 下专属目录 | 显式创建 `OhosCrashRecorder` 收系统延迟 APP_CRASH 原始 JSON | HiAppEvent API 12+；未导入 external_log 栈附件，无 HAR/ArkTS 桥 |
| JVM | 相同文件逻辑 | 无系统采集器 | 用于普通 JVM 宿主与行为验证 |

Android 24/25 的 rename 使用 `File.renameTo`，同文件系统的 Android/Linux rename 保持原子性；其他实际使用的 kotlinx-io 方法为 `java.io.File`、`FileInputStream`、`FileOutputStream`，不依赖 API 26 才有的 `java.nio.file.Files`。JVM/Native 使用 kotlinx-io 原生 atomicMove。

依赖：Kotlin `2.2.21-1.0.0`（OHOS fork）、AGP `8.10.1`、`kotlinx-io-core:0.9.0-1.0.0`（包含 OHOS 变体）。不依赖 Tinylog、Ktor、Compose、Kuikly 或宿主业务模块。发布候选采用 Apache-2.0；来源及许可确认状态见 [SOURCE.md](SOURCE.md)。

## 初始化与导出

```kotlin
// Android：使用应用单例，不要每个页面创建同一目录的实例。
val diagnostics = androidDiagnosticStore(applicationContext)
// 宿主在自己已有的有界后台队列调用，明确采用自己的脱敏策略。
diagnostics.append("INFO/Startup: host-provided safe message")
diagnostics.recordReport(ReportKind.SYSTEM, safeDiagnosticText)

val batch = diagnostics.prepareBatch()
if (batch.files.isNotEmpty()) {
    val receipt = diagnostics.exportBatch(batch, privateExportDirectory)
    // receipt.path 是标准 .tar；可用系统 tar 解包，或由宿主分享/上传。
    // 分享/上传失败时保留 receipt 和 pending；再次 prepareBatch 得到同一批次。
    diagnostics.deleteExported(receipt) // 仅在宿主明确完成后调用，不自动调用。
}
```

公共 API 包含 `DiagnosticStore`、`DiagnosticLimits`、`ReportKind`、文件描述、稳定批次、流式 reader 与导出回执，平台采集器独立启用。`append` 接收已经由宿主格式化、脱敏的单行/多行文本，不隐式加设备、账号或线程元数据。

Android 宿主显式保留 `val recorder = AndroidCrashRecorder(diagnostics)`；重复安装会抛错，先撤回上一采集器，安装/释放由宿主串行执行。停止时 `recorder.close()` 仅在它仍是当前默认 handler 时恢复旧 handler；与其他 crash SDK 同用必须按安装逆序释放。随后调用 `diagnostics.close()`。异常写入失败不会阻止旧系统 handler；不保证磁盘失败、OOM 或进程硬杀时落盘。

iOS 把 Swift 文件加入宿主 target（它不是 KMP Maven 内的 Swift Package），在主线程调用 `start`/`stop`，回调写入宿主持有的 store：

```swift
let recorder = GYMetricKitRecorder { text in
    // 用宿主自己的 KMP Framework 名称与 Swift 导出签名接入：
    // do { _ = try store.recordReport(kind: .system, text: hostSanitize(text)) }
    // catch { /* 宿主记录轻量失败状态；原有批次不删除。 */ }
}
recorder.start()
// stop 返回前等待已经接受的回调落盘；之后再 close KMP store。
recorder.stop()
```

文件 API 使用 `@Throws` 向 Swift 导出 NSError，宿主用 `try`/`catch` 处理文件系统失败。若宿主向 Swift 导出自己的薄包装函数，也要保留 `@Throws`；该声明不会自动穿过包装层。

MetricKit 不订阅常规 metrics payload，只处理 `MXDiagnosticPayload`（Crash/Hang 等系统 JSON）。本轮独立 Maven 消费者 Framework 的 NSError 导出与 Swift 回调接线类型检查已通过；真实业务宿主的生命周期接线、后台运行和真机投递仍需由宿主验证。

OHOS 宿主传 `DiagnosticStore("$filesDir/gycrosskit-diagnostics")`，需要时创建并持有 `OhosCrashRecorder(store)`；关闭前先 `recorder.close()`。本进程只允许一次成功安装，关闭后不能重装：API 12 的 C 回调只带事件 domain，卸载不等待已取得 observer 的回调，无法可靠区分旧实例；安装失败仍可重试。RemoveWatcher 失败抛错并保留句柄供重试；系统回调不能抛异常，保存失败只丢失该次事件，原有文件仍保留。采集器只保存系统投递原文，不触发崩溃、不自行访问附件或用户目录。

## 逐文件上传与落盘通知

逐文件协议无需解析 TAR 或访问私有路径：

```kotlin
val batch = diagnostics.prepareBatch()
for (file in batch.files) {
    val reader = diagnostics.openFile(batch, file.id)
    try {
        while (true) {
            val chunk = reader.read() // 默认 16 KiB，单次最多 64 KiB
            if (chunk.isEmpty()) break
            hostUploadChunk(batch.id, file.id, file.size, chunk)
        }
    } finally { reader.close() }
}
// 仅当整个批次全部上传成功、reader 已关闭后确认；失败/部分上传直接保留批次。
diagnostics.acknowledgeBatch(batch)
```

`file.id` 与 `batch.id` 共同组成稳定标识，描述中不含内部路径。`openFile` 校验批次身份和完整文件集，
reader 有界读取并检查冻结尺寸；宿主自行关闭 reader，store.close 不代替关闭独立 reader。
重启后重新 `prepareBatch` 取得同 id/文件集，再打开文件；旧实例对象不能跨实例确认。
`acknowledgeBatch` 只删除经校验的 pending 批次，不删除后来产生的 root 日志。
它是宿主完成逐文件上传后的明确确认，组件不猜测上传成功；原 `exportBatch/deleteExported` TAR 校验流程保留。

Android/OHOS 采集器可传 `onReportStored: (ReportKind) -> Unit`。只有原子保存成功后通知，
容量满、写失败、close 后事件均不通知；通知只传种类，不传诊断原文。通知在文件锁外执行，
生命周期门使用可重入锁，允许通知内读取 store 或关闭采集器；close 返回前等待已开始的通知。
回调应立即向宿主队列提交任务，不能同步等待另一个调用 close 的线程。
宿主通知异常不影响系统 handler/后续事件，也不会越过 OHOS C ABI。

```kotlin
val recorder = OhosCrashRecorder(diagnostics) { kind -> hostSchedulePendingUpload(kind) }
// AndroidCrashRecorder(diagnostics) { kind -> hostSchedulePendingUpload(kind) }
```

iOS 使用 `GYMetricKitRecorder(record:onReportStored:)`，record 只在实际写入成功时返回 true；
旧 Void record 初始化方式仍可使用。通知在采集队列上执行，不持有状态锁；宿主调度上传与隐私准入。

```swift
let recorder = GYMetricKitRecorder(record: { text in
    do { return try store.recordReport(kind: .system, text: hostSanitize(text)) }
    catch { return false }
}, onReportStored: { hostSchedulePendingUpload() })
```

## 大小、失败与生命周期

- 默认每日志卷 5 MiB，活动卷在内共 5 卷；按大小轮转，不按日历轮转。超长文本在 UTF-8 字符边界截断，卷不会因单条消息超限。
- 诊断报告最大 512 KiB；root 与 pending 合计最多 5 份。已满返回 `false`，不覆盖已有待确认报告。报告种类 CRASH/HANG/SYSTEM 只是宿主指定的分类。
- 每批最多 20 文件、60 MiB；超出限额的文件留在原目录。没有一个候选可容纳时抛错，不把超限当成空成功。
- `prepareBatch` 使用同文件系统 rename 冻结日志和报告。已有 pending 优先复用，进程重启后 id 保持不变；新实例必须重新 prepare，旧实例回执拒绝使用。
- pending 不参与滚动淘汰，最多额外保留一批容量。导出生成无压缩 POSIX ustar，逐次只复制 16 KiB；完整结束后 `.tmp` rename 为 `.tar`。不把整个批次读入内存。
- `deleteExported` 先验证批次 id、完整文件集和尺寸，再逐字节验证 TAR 的头、内容与尾部，全部通过才删 pending；新增的 root 日志/报告不会被删。归档缺失、损坏、截断或源文件变化均保留原批次。删除 I/O 失败可能已删掉部分已确认文件，剩余 pending 可重新 prepare；没有回滚删除承诺。
- 一个专属目录只能由一个进程中的一个实例管理。无跨进程锁，不扫描宿主已有的任意诊断目录。所有文件操作在实例锁内串行且同步，导出期间会阻塞同实例写入；宿主必须在后台队列调用，不要从 UI 主线程调用。组件不维护无界队列。
- 每次写入关闭文件，没有 `flush` 需求；`close` 拒绝后续调用。stdio 关闭/rename 提供正常退出的可见性，不承诺断电 durability。系统采集器必须先停止并撤回，宿主负责遵循平台生命周期。
- 导出副本由宿主管理与清理；组件不限制宿主创建多少份 TAR。使用应用私有输出目录，分享需宿主配置 FileProvider/分享授权；不会自动暴露文件。

## 本地验证

```bash
bash gradlew :diagnostics-core:jvmTest :diagnostics-core:testDebugUnitTest :diagnostics-core:compileDebugKotlinAndroid --offline --max-workers=1
bash gradlew :diagnostics-core:compileKotlinIosArm64 :diagnostics-core:compileKotlinIosSimulatorArm64 :diagnostics-core:compileKotlinOhosArm64 --offline --max-workers=1
bash gradlew :diagnostics-core:iosSimulatorArm64Test --offline --max-workers=1
xcrun --sdk iphonesimulator swiftc -typecheck ios-support/GYMetricKitRecorder.swift -target arm64-apple-ios14.0-simulator
bash gradlew :diagnostics-core:publishAllPublicationsToStagingRepository --offline --max-workers=1
bash gradlew -p verification-consumer -PdiagnosticsMavenRepo="$PWD/build/maven" jvmTest compileDebugKotlinAndroid compileKotlinIosArm64 compileKotlinIosSimulatorArm64 compileKotlinOhosArm64 linkDebugFrameworkIosSimulatorArm64 linkDebugSharedOhosArm64 --offline --max-workers=1
# Maven 消费者 Framework 构建后，可联合验证 Swift 错误处理接线：
xcrun --sdk iphonesimulator swiftc -typecheck ios-support/GYMetricKitRecorder.swift verification-consumer/SwiftConsumer.swift -F verification-consumer/build/bin/iosSimulatorArm64/debugFramework -target arm64-apple-ios14.0-simulator
```

独立 `verification-consumer` 通过 exclusive Maven 仓库解析正式发布物，不使用 project dependency、includeBuild 或 mavenLocal。行为测试覆盖滚动容量、UTF-8、并发写入、重启批次复用、报告/批次容量、TAR 工具逐字节解包、归档损坏与写入中途失败保留源文件、确认只删原批次。

准确已执行结果记录在 [VALIDATION.md](VALIDATION.md)。Android/iOS/OHOS 真机采集、系统真实 crash/hang 投递、后台退出与分享权限、真实业务宿主的 Swift/KMP 整合、远程 Maven/JitPack/ohpm 均未在本轮验收。
