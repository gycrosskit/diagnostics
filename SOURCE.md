# 来源与归属

本组件依据宿主授权，对以下本地源码的实际行为重新整理为独立通用能力：

- `shared-platform` 的 Android `TinylogLogger`、`CrashReportStore`、`UncaughtExceptionLogger`、`AndroidDiagnosticFileProvider`。
- iOS `IosLogStore`、`IosDiagnosticFileProvider` 和 Swift `DiagnosticCollector`。
- OHOS `OhosDiagnosticFileProvider`、`OhosCrashWatcher`。
- `shared-business` 的 `Logger`、`DiagnosticFileProvider`、`LogUploadRepository`，仅用于确认宿主边界。

来源工作区：`harmony-production-integration/sxmqliveAndroid`。来源未发现公开 LICENSE；本轮仅本地源码交付，未授予第三方再分发许可、未远程发布。现有源码所有者的权利保留，公开发布前由所有者确定组件许可证。

独立实现使用 `kotlinx-io-core 0.9.0-1.0.0`（JetBrains / OpenHarmony fork，Apache-2.0）。没有复制 tinylog、Ktor、账号/用户、网络上传或品牌依赖。单文件 ustar 编码为本组件实现。
