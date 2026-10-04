# 2026-10-04 M01–M05 未发布候选本地验收

当前候选在 `codex/remote-dependency-closure` 独占 Worktree 实现；以下验证不属于旧 `0.2.0-rc.1` 发布，也不代表远程坐标已经更新。

| 检查 | 实际结果 |
| --- | --- |
| core JVM | 19 tests，0 failures/errors；旧冻结 id/重启/全批 ack、修改原件保留、容量失败、active/frozen 同名 ZIP、尾读、SHA-256、writer flush/timeout/interruption、Throwable Unicode/cause/suppressed、网络并发 |
| core Android | 18 tests，0 failures/errors；包括系统 handler/落盘+flush 双失败仍转交、重复安装、原 ANR 契约 |
| core iOS Simulator | 10 tests，0 failures/errors；包含五种 MetricKit 数组、NSException、畸形/嵌套/有界解析与 SHA-256 向量 |
| dingtalk JVM/Android/iOS Simulator | 各 4 tests，0 failures/errors；固定签名、官方 endpoint 拒绝、HTTP/协议失败、响应上限、redirect、取消、关闭；全部 MockEngine，未发真实通知 |
| 多平台编译 | core Android/iOS arm64/simulator arm64/x64/OHOS arm64；dingtalk Android/JVM/iOS arm64/simulator arm64/x64 均成功 |
| staging | `publishAllPublicationsToStagingRepository` 成功；metadata 正规化和 `verification/check-maven.py` 核验全部 **13 modules**，两份 Android AAR、core7+dingtalk6 变体引用/size/SHA 齐全 |
| 独立 staging 消费 | `verification-consumer` exclusive 仓库，无 project/includeBuild/mavenLocal；`-PdiagnosticsClosure=true` 消费新 API与可选渠道，JVM1/Android2 tests、iOS arm64/simulator与OHOS编译成功；显式默认层级保留 iOS 新 API 真实编译 |

日志在 Worktree 忽略目录 `build/closure/`：`jvm-android-first.log`、`jvm-android.log`、`dingtalk-jvm-android.log`、`native-compile.log`、`native-test.log`、`staging-all.log`、`consumer-staging.log`。

首次 JVM 测试发现 macOS `/var` → `/private/var` 祖先目录别名误判，已修复并重跑通过，源文件符号链接仍拒绝。可选渠道首次 offline 解析失败源于 Nexus 原 regex 未包含 Ktor 的 `-1.1.0-04` 后缀，现仅扩展既有 fork 后缀，正常解析后离线编译/测试通过。没有改依赖版本：均与宿主 catalog 一致。staging 检查器曾错误按 component 根 identity 区分平台模块，现按实际 Maven artifact 目录检查；独立 consumer 初次自定义 sourceSet 导致 iOS 层级未连接，已显式 `applyDefaultHierarchyTemplate()` 后重跑通过。

未执行：候选发布/PR/tag/Release/JitPack 正式远程消费、真实宿主旧主体删除后的设备行为、真实 NSException/MetricKit 投递、真实钉钉发送。Swift 采集源码未修改；既有 Swift Package/Pod 验证不能代替本候选 Maven 新 API 验证。root 后续发布归档须保留全部13modules且重新校验不可变标签版本。

# 2026-10-04 0.2.0-rc.1 正式远程验收

[实现 PR #3](https://github.com/gycrosskit/diagnostics/pull/3) 已按 main 保护合并，发布提交 `88091275f5bf8190a9510d12873a4e30181ba484`。不可变 tag `0.2.0-rc.1` 与 [GitHub prerelease](https://github.com/gycrosskit/diagnostics/releases/tag/0.2.0-rc.1) 已创建；后续文档不移动标签或覆盖附件。

| 检查 | 实际结果 |
| --- | --- |
| JitPack | 最终 `status=ok`、`isTag=true`，commit 与发布提交一致，共 7 个模块 |
| 正式 Maven GAV | `com.github.gycrosskit.diagnostics:diagnostics-core:0.2.0-rc.1`，独立工程 exclusive JitPack 解析，无 staging/mavenLocal/includeBuild/project 替换 |
| Android/JVM 消费者测试 | Android 2 个公开 `BoundedReportWriter` Crash 格式/UTF-8/marker 测试、JVM 1 个测试，均 0 failures/errors |
| 多平台消费 | Android、iOS arm64/simulator arm64/x64、OHOS arm64 编译；Simulator Framework 与 OHOS so 链接通过 |
| Git tag Swift Package | 远程 exact `0.2.0-rc.1` 解析到发布提交，iOS arm64 device / arm64 simulator SDK 编译通过 |
| Git tag Pod | `pod spec lint` 实际下载 podspec 的 Git/tag 并构建消费 App，通过；非本地 path/lib lint |
| Swift/KMP 联合契约 | 远程 SwiftPM module + 正式 Maven consumer Framework typecheck 通过，兼容 recorder/NSError 读取与通知 API 可消费 |
| 远程字节 | 7 个下载二进制与 staging 逐字节相同；9 个 variant 文件引用 size/SHA 和四种 Native target 齐全 |

Gradle 使用正常 `/Users/guoyang/.gradle`；本轮独立新工程刷新正式 JitPack，完整矩阵 `BUILD SUCCESSFUL in 1m 5s`。初次轻量 iOS arm64 文件下载发生连接 reset，有界重试后字节/hash 检查通过。

JitPack 响应把根 component identity 改为 `com.github.gycrosskit:diagnostics`；组织 checker 对原始 staging/归档的组名断言不能直接套用该下载树。本轮保留服务器响应，按真实 size/hash/平台变体与实际 Gradle 解析、编译、链接验证，没有修改远程 metadata。

GitHub Release asset digest 已核对：

- `diagnostics-maven.tar.gz`：`ad6e60c95c41f294c328990368003e3be11cf826c63f7f622422045a36cf521f`。
- `diagnostics-native.tar.gz`：`fb98f3ade6d5266611c27014acae65ca505a555adeb2e2b7999bacc69fb027fe`。

两包无 AppleDouble/xattrs，旧 0.1.0 checksum 保留。JitPack 安装日志确认新 Maven archive checksum OK。实际 Android ANR、iOS NSException/MetricKit 最终系统投递、后台生命周期及业务上传/通知仍未真机验收；SDK 编译与 mock 测试不证明最终递送。

以下保留实现阶段的历史验证快照。

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

## root 发布准备 0.2.0-rc.2

新增可选 diagnostics-dingtalk 与 core 合计 13 Maven module（core7/channel6）；同版全平台 staging 再构建与 Android crash callback 旧位置参数兼容通过编译/测试。归档正规化后全部文件引用/大小/SHA 检查通过，SHA256=22ad4fca03094f34208dc7b626dc7724db222cd2e056bd9cb988b64696230461。旧版标签不覆盖；待 GitHub/JitPack 精确发布后另核真正远程消费。

最终源码复核修复 export 临时目标碰撞和 writer 写入恢复：21 项 JVM 测试通过；完整 13 module 重新 staging、metadata/实体文件 SHA 校验通过。验收用消费者版本改为可指定候选 rc.2，远程结果另记。
