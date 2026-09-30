# 来源与归属

本组件依据宿主授权，对以下本地源码的实际行为重新整理为独立通用能力：

- `shared-platform` 的 Android `TinylogLogger`、`CrashReportStore`、`UncaughtExceptionLogger`、`AndroidDiagnosticFileProvider`。
- iOS `IosLogStore`、`IosDiagnosticFileProvider` 和 Swift `DiagnosticCollector`。
- OHOS `OhosDiagnosticFileProvider`、`OhosCrashWatcher`。
- `shared-business` 的 `Logger`、`DiagnosticFileProvider`、`LogUploadRepository`，仅用于确认宿主边界。

来源工作区：`harmony-production-integration/sxmqliveAndroid`。来源未发现公开 LICENSE；本组件采用 Apache-2.0（见 LICENSE），源码所有者已于 2026-09-30 授权公开发布并确认许可证。组件的许可证只覆盖本仓库的通用实现。

独立实现使用 `kotlinx-io-core 0.9.0-1.0.0`（JetBrains / OpenHarmony fork，Apache-2.0）。没有复制 tinylog、Ktor、账号/用户、网络上传或品牌依赖。单文件 ustar 编码为本组件实现。
