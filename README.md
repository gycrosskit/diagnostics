# GY CrossKit Diagnostics

应用私有滚动日志、诊断报告、稳定批次逐文件读取及可重试 TAR 导出。组件负责本地有界存储，宿主负责隐私准入、脱敏、后台调度、分享或上传。

当前 Maven 版本为 **0.1.0**，见 [Release](https://github.com/gycrosskit/diagnostics/releases/tag/0.1.0)。JitPack 产物已通过独立 Android、iOS 与 OHOS 消费验证；无 HAR / Swift Package。

## 支持范围

| 平台 | 存储入口 | 可选采集与限制 |
| --- | --- | --- |
| Android API 24+ | `androidDiagnosticStore(Context)`，noBackupFilesDir | `AndroidCrashRecorder`：JVM 未捕获异常，不捕获 Native signal / ANR |
| iOS | `iosDiagnosticStore()`，Library 并排除备份 | 独立 Swift `GYMetricKitRecorder`，iOS 14+，系统延迟 Crash/Hang JSON |
| OpenHarmony | `DiagnosticStore`，宿主传 filesDir 下专属目录 | `OhosCrashRecorder`：HiAppEvent API 12+，延迟 APP_CRASH；无 ArkTS 桥 |
| JVM | `DiagnosticStore` | 文件存储，无系统采集器 |

Kotlin **2.2.21-1.0.0** OHOS 工具链；依赖包含 OHOS 变体的 `kotlinx-io-core:0.9.0-1.0.0`。不依赖 Compose、Kuikly 或业务模块。

## 安装

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.gycrosskit.diagnostics") } }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
    }
}
// commonMain.dependencies
implementation("com.github.gycrosskit.diagnostics:diagnostics-core:0.1.0")
```

系统采集独立启用。iOS 将 [GYMetricKitRecorder.swift](ios-support/GYMetricKitRecorder.swift) 加入宿主 target，再连接宿主导出的 KMP Framework；添加 Maven 依赖不会自动安装 MetricKit 采集器。

## 最小使用

```kotlin
import io.github.gycrosskit.diagnostics.androidDiagnosticStore
import io.github.gycrosskit.diagnostics.ReportKind

val store = androidDiagnosticStore(applicationContext)
// 在宿主有界后台队列执行，文本由宿主先脱敏。
store.append("INFO/Startup: host-provided safe message")
val saved = store.recordReport(ReportKind.SYSTEM, safeDiagnosticText)
val batch = store.prepareBatch()
if (batch.files.isNotEmpty()) {
    val receipt = store.exportBatch(batch, privateExportDirectory)
    // 宿主分享/上传 receipt.path；失败时保留 receipt 和 pending。
    // 仅在宿主明确完成后：store.deleteExported(receipt)
}
```

类型及平台 factory 位于 `diagnostics-core`，具体 import、逐文件上传、Swift 错误处理及采集器见[接入指南](docs/接入指南.md)。`privateExportDirectory` 必须为应用私有绝对路径，位于 store 根目录之外；同一专属目录只由一个进程中的一个实例管理。

## 数据与生命周期

- 同步文件操作在实例内串行；宿主使用后台队列。默认日志每卷 5 MiB、共 5 卷，报告最大 512 KiB、最多 5 份，批次最多 20 文件 / 60 MiB；容量满不覆盖待确认报告。
- `prepareBatch` 冻结并复用 pending，重启后批次 id 保持稳定。逐文件 reader 必须关闭；全部上传成功后才 `acknowledgeBatch`。上传失败/部分成功保持批次，不自动删除。
- TAR 导出有界流式复制；`deleteExported` 验证归档与完整源文件后才删除 pending。删除 I/O 失败可能部分完成，剩余批次可重试，不承诺回滚删除。
- 先停止并撤回系统采集器，再关闭 store。OHOS 一个进程只允许一次成功安装采集器，关闭后不能重装；iOS 系统诊断为延迟投递，不承诺即时或必达。
- 落盘通知仅在保存成功后触发，不包含原文。回调应立即提交宿主队列，不同步等待另一个执行 close 的线程。Swift 文件 API 通过 `@Throws` 导出 NSError，包装层也需保留声明。

组件不自动采集账号、上传数据、请求用户授权、脱敏或触发模拟崩溃。实际 crash/hang 投递、后台生命周期和系统分享权限仍需真机验收。

## 文档与反馈

- [接入指南](docs/接入指南.md)：容量、失败重试、采集器、逐文件协议与落盘通知。
- [开发与验证](docs/开发与验证.md) · [验证记录](VALIDATION.md) · [来源](SOURCE.md)。
- [Releases](https://github.com/gycrosskit/diagnostics/releases) · [Issues](https://github.com/gycrosskit/diagnostics/issues)：提供版本、平台及脱敏复现。

由 GY CrossKit 维护，采用 [Apache-2.0](LICENSE)。
