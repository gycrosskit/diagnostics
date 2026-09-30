# 本轮实际验证（2026-09-30）

源码位于独立新目录；全部写入/构建在本目录完成。仅 `git init -b codex/diagnostics-component`，没有提交、推送、远程建仓库、合并、打 Tag、发布或业务宿主接线。

## 已完成

| 范围 | 命令/证据 | 结果 |
| --- | --- | --- |
| JVM 行为 | `:diagnostics-core:jvmTest` | 7 个，0 失败/跳过；滚动、UTF-8、报告/批次上限、重启、并发、失败保留、系统 tar 解包 |
| Android JVM 单测 | `:diagnostics-core:testDebugUnitTest` | 6 个，0 失败/跳过；包括 5 个 common 文件行为与 recorder 安装/撤回/重复拒绝；未模拟崩溃 |
| Android 编译/产物 | `compileDebugKotlinAndroid` 与 staging publication 的 Release AAR | 成功；minSdk 24 |
| iOS 编译 | `compileKotlinIosArm64` / `compileKotlinIosSimulatorArm64` / staging 中 `compileKotlinIosX64` | 全部成功 |
| iOS Simulator 行为 | `:diagnostics-core:iosSimulatorArm64Test` | 5 个 common 文件测试，0 失败/跳过；测试 XML 在 `diagnostics-core/build/test-results/iosSimulatorArm64Test/` |
| OHOS 编译 | `:diagnostics-core:compileKotlinOhosArm64` | 成功；真实 HiAppEvent Kotlin/Native API 编译，未运行事件回调 |
| Swift 采集源文件 | `xcrun --sdk iphonesimulator swiftc -typecheck ios-support/GYMetricKitRecorder.swift -target arm64-apple-ios14.0-simulator` | 成功；不是 Swift Package 发布/宿主桥接验证 |
| Swift/KMP 消费签名 | 最新 Maven KLIB 导出 Framework，`SwiftConsumer.swift` + MetricKit 源码联合 swiftc typecheck | 成功；recordReport 等文件 API 与 iOS factory 包装入口的 header 含 NSError，Swift `try`/`catch` 与 `try IosConsumerKt.iosStore()` 类型检查通过；没有启动采集器 |
| 本地 Maven | `:diagnostics-core:publishAllPublicationsToStagingRepository` | 成功；7 module metadata，36 个文件/目标 metadata 引用全部存在 |
| 独立 Maven JVM 消费 | consumer `jvmTest` | 1 个通过；使用坐标构造 store、生成 TAR，系统 tar 读取，确认删除 |
| 独立 Maven Android/iOS/OHOS 消费 | consumer 四端 compile、`linkDebugFrameworkIosSimulatorArm64`、`linkDebugSharedOhosArm64` | 全部成功；exclusive staging 仓库，无 project/includeBuild/mavenLocal |
| 发布模板 | `bash -n jitpack-install.sh`、Python AST 语法检查、与共用模板 byte compare | 成功；空 checksum 的 `VERSION=0.1.0 bash jitpack-install.sh diagnostics` 预期失败并在联网前停止 |

所有 Gradle 验证使用 `bash gradlew --offline --max-workers=1`。最新消费者执行 `--refresh-dependencies` 强制重新解析最新本地 staging；没有使用源码项目依赖。

```bash
bash gradlew :diagnostics-core:jvmTest :diagnostics-core:testDebugUnitTest :diagnostics-core:compileKotlinIosArm64 :diagnostics-core:compileKotlinOhosArm64 :diagnostics-core:iosSimulatorArm64Test :diagnostics-core:publishAllPublicationsToStagingRepository --offline --max-workers=1
bash gradlew -p verification-consumer -PdiagnosticsMavenRepo="$PWD/build/maven" jvmTest compileDebugKotlinAndroid compileKotlinIosArm64 compileKotlinIosSimulatorArm64 compileKotlinOhosArm64 linkDebugFrameworkIosSimulatorArm64 linkDebugSharedOhosArm64 --offline --refresh-dependencies --max-workers=1
```

本地坐标 `io.github.gycrosskit:diagnostics-core:0.1.0`，变体 Android、JVM、iOS arm64/x64/simulator arm64、OHOS arm64。产物：

- `build/maven/`：本地 staging Maven 仓库。
- `build/diagnostics-maven.tar.gz`：仅本地 Maven 归档，295263 bytes。
- SHA-256：`73b7319eebf97ede179a2a904340aede1337ae7c86cc307d0184139c19b22016`；旁边 `.sha256` 文件可核验。
- `verification-consumer/build/bin/iosSimulatorArm64/debugFramework/DiagnosticsConsumer.framework`：消费端 Framework。
- `verification-consumer/build/bin/ohosArm64/debugShared/libDiagnosticsConsumer.so`：消费端共享库，不是 HAR。

## 修正与提示

OHOS watcher 初轮编译暴露两类问题：opaque handle 实际来自 `cnames.structs.HiAppEvent_Watcher`；类内 `runCatching` 可解析为有 receiver 的扩展，造成 C 回调捕获 `this`。已改为明确类型导入、文件级状态和不捕获 receiver 的 try/catch，最终编译/消费者链接均成功。

审查实际 `kotlinx-io` JVM 源码发现 `atomicMove` 依赖 API 26 才存在的 `java.nio.file.Files`。Android actual 已改为来源原有的 `File.renameTo` 同文件系统 rename，JVM/Native 保持 kotlinx-io。当前实际使用的 exists/delete/createDirectories/metadata/resolve/list/Path 与 source/sink 都只走 java.io.File/FileInputStream/FileOutputStream；Android JVM 文件测试直接覆盖 rename actual。没有把 API 24/25 源码兼容性检查说成真机验收。

文件 I/O 入口与构造器补 `@Throws`，通过已发布 KLIB 消费者 Framework header 的 NSError 参数与联合 Swift 类型检查验证；消费端 factory/导出薄包装也保留 @Throws，已单独重链 Framework 验证 factory NSError 签名；不会把普通文件异常作为未声明 Kotlin 异常传播给 Swift。

编译仍有 Kotlin expect/actual class Beta 提示。OHOS 消费者 shared lib 链接的 fork CAdapterGenerator 生成 `api.cpp` 出现两个 non-void return 警告；链接成功，未修改生成器或依赖源码。

## 未执行

Android API 24/25 或其他真机、Android native crash/ANR/硬杀、iOS 真机 MetricKit Crash/Hang 延迟投递、真实业务宿主的 Swift/KMP 接线/运行、OHOS 真机系统 APP_CRASH 与 lifecycle、分享授权与真实网络上传/隐私流程。组件本身没有上传功能。

未执行 GitHub/Gitee 远程建仓库、main 保护、JitPack/Release/ohpm/Swift Package 发布或远程 Maven 消费。`release-checksums.txt` 为空；未来发布归档需要用正确 JitPack group 构建、确定许可证并经远程验证，不能把本地 io.github 坐标归档直接称为已发布 JitPack 产物。

Swift/KMP 消费签名复验：

```bash
xcrun --sdk iphonesimulator swiftc -typecheck ios-support/GYMetricKitRecorder.swift verification-consumer/SwiftConsumer.swift -F verification-consumer/build/bin/iosSimulatorArm64/debugFramework -target arm64-apple-ios14.0-simulator
```
