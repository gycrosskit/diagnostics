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
