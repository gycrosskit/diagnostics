# 验收记录（2026-09-30）

任务分支 `codex/diagnostics-contract-fix`；原始基线 `31b48da`。修复 Issue #10，并准备 JitPack 0.1.0。除用户授权的原始初始提交外，尚未提交修复或远程发布。

| 检查 | 结果 |
| --- | --- |
| JVM 行为 `:diagnostics-core:jvmTest` | 9 个，0 失败；冻结、重启复用、逐文件有界读取、确认只删原批次、TAR 保持兼容 |
| Android `testDebugUnitTest` | 9 个，0 失败；含真实 recorder 的 mock handler 路径，成功通知、容量满不通知、下游 handler、close 后旧 handler 不复活 |
| iOS Simulator `iosSimulatorArm64Test` | 7 个，0 失败；包括文件锁外通知、读取 store 重入、通知中 close、通知异常隔离 |
| Android/iOS arm64/simulator/OHOS 源码 | 编译成功；iOS x64 通过全 staging 发布编译 |
| 正式 group 全 staging Maven | 成功，`com.github.gycrosskit.diagnostics:diagnostics-core:0.1.0`；最终 OHOS close 修复重新发布该变体 |
| 独立 Maven 消费 | JVM test、Android/iOS arm64/simulator/OHOS 编译，Simulator Framework 与 OHOS shared library 最终链接成功；无 project/includeBuild/mavenLocal |
| Swift 源码及导出契约 | 最终 consumer Framework + MetricKit + SwiftConsumer.swift 联合 typecheck 成功，覆盖逐文件 reader、明确确认、Bool 落盘结果通知和 NSError |
| SDK 生命周期只读复核 | API 12 OnReceive 不含 watcher 身份；官方 observer 快照可能在 RemoveWatcher 后回调。成功安装后保留进程 reservation，防止关闭后重装误投新实例；安装失败可重试 |
| Git/产物 | `git diff --check` 通过；Maven metadata 文件存在/大小/SHA256 检查通过，归档在 `build/release`，版本校验写入 release-checksums.txt |

所有新 API 的 JVM/Android/iOS 测试均执行，OHOS 仅编译与最终消费链接；没有运行真实系统崩溃投递、真机、后端上传、业务宿主 loading/隐私调度。采集器只传 ReportKind，上传与隐私门控仍由宿主控制。OHOS 采集器本进程只成功安装一次的限制见 README。

本机消费者首次缺少 Android sdk.dir，补独立消费工程的忽略配置后通过。并行构建遇 16GB 内存拥挤，取消本任务排队构建后采用串行 worker1 / 1GB / no-daemon 成功；未取消其他聊天构建。

远程 GitHub/JitPack 发布、独立远程 Maven 消费尚待完成。Apache-2.0 已由所有者确认；来源见 SOURCE.md。

## 2026-09-30 远程验收结果

Maven `0.1.0` 已发布：[GitHub Release](https://github.com/gycrosskit/diagnostics/releases/tag/0.1.0)，JitPack 状态 `ok`，独立消费的Android、iOS arm64/x64 编译、iOS Simulator Framework 链接、OHOS 编译通过。 JVM 行为测试、OHOS `.so` 链接和消费 Framework 的 Swift 读取/通知 API typecheck 通过；本库无 HAR。

本轮默认远程仓库解析，无源码 include/project 替换或 mavenLocal。Gradle 消费使用 `--rerun-tasks` 强制编译；permission、diagnostics 同时刷新依赖，其余库使用新版本首次远程解析，`--info` 留有 JitPack 下载证据。行为测试、SDK mock 与产物消费不代表真机系统页面或真实授权/支付验收。
# 2026-10-04 原生采集候选 0.2.0-rc.1

宿主首次编译补充：原 ANR 文件中的 `BoundedReportWriter` 同时被宿主 `CrashReportStore` 使用。现公开同一 Android writer，宿主 import `io.github.gycrosskit.diagnostics.BoundedReportWriter`；UTF-8 字节预算、代理对和自定义 marker 实现未改。组件 14 个 Android 测试复跑通过，Android Release AAR/sources 重新 staging；独立 Maven Android 消费者新增并通过 2 个 Crash 格式 writer 测试，覆盖 512 KiB、Unicode、marker 与未截断格式。metadata 正规化、7 modules checker 和更新归档解包检查通过。其他平台源码与二进制未重构或重建。

任务分支 `codex/diagnostics-collectors`；独占 Worktree `native-component-extraction/diagnostics`。实现验证阶段没有执行 commit/push/tag/release；后续发布已获明确授权，不把旧 0.1.0 的远程成功视为新版本验收。

| 检查 | 实际结果 |
| --- | --- |
| Android `testDebugUnitTest` / `compileDebugKotlinAndroid` | 通过，14 个测试；含原有 store/crash 回归和 5 个 ANR 容量/去重/解析/线程边界测试 |
| JVM `jvmTest` | 通过，9 个测试 |
| 全部 staging publication | 通过，Android Release、JVM、root metadata、iOS arm64/simulator arm64/x64、OHOS arm64，共 7 modules，版本 0.2.0-rc.1 |
| Maven metadata / 实体产物 | 组织模板正规化后 checker 成功，校验文件 hash、依赖和全部平台变体；归档解压后 checker 再成功 |
| 独立 Maven consumer | 通过：1 个 JVM 测试、Android public ANR API、iOS arm64/simulator arm64 与 OHOS 编译、iOS Simulator Framework/OHOS so 链接；正规化后 refresh-dependencies 再验证通过 |
| Swift Package 独立 product consumer | arm64 iOS Simulator / arm64 iOS device SDK 均通过编译 |
| Swift + KMP Framework | 全部 ios-support Swift 与独立 consumer Framework 的联合 typecheck 通过，兼容 recorder/NSError 接口仍可消费 |
| CocoaPods | `pod lib lint GYDiagnosticsNative.podspec --allow-warnings --no-clean --use-libraries` 通过，实际生成 Pod App 消费工程并编译/import |
| `git diff --check`、installer shell syntax | 通过 |

初次 Android 构建未指定 SDK，报告 SDK location not found；提供本机 ANDROID_HOME 后重跑成功。首次 Swift 编译暴露 NSException handler 的 C calling convention，需要显式 `@convention(c)` 保存旧 handler，修复后两种 SDK 与 Pod 均成功。归档检查最初使用系统 Python 不支持的 tarfile `filter` 参数，改为本地 tar 解包后实体 checker 成功。

本地产物在 `build/releases/0.2.0-rc.1/`：`diagnostics-maven.tar.gz`、`diagnostics-native.tar.gz` 与 `SHA256SUMS`。Maven 包来自本地 staging，Native 包是完整 Package/Pod 源码；两包均检查无 AppleDouble/xattrs。旧 `release-checksums.txt` 保留；发布准备登记本轮经检查的 Maven SHA-256。

尚未执行真实 Android ANR/进程退出 trace、iOS NSException/MetricKit 最终 crash/hang 递送、后台 lifecycle、宿主隐私准入/通知/上传及远程标签下载验收。Swift device SDK 编译不等于真机递送。ANR close 同步 drain，系统 trace I/O 无期限，宿主须后台调用；Swift 采集所有权强持有实例，宿主必须显式 stop。
