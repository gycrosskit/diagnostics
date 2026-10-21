# @gycrosskit/diagnostics-umeng

提供可选的友盟诊断适配器，依赖同版本 diagnostics-core。

**当前源码含未发布的 InternalPlugin 接入修复。下面的已发布 rc.16 坐标不含该修复；本轮不更新版本或发布归档。**

```bash
ohpm install @gycrosskit/diagnostics-umeng@0.2.0-rc.16
```

由宿主显式选择供应商并传入配置，组件不依赖业务宿主。安装库不代表已完成设备、供应商服务或上报验收。

纯 ArkTS 实现，不打包额外的 Kotlin runtime。接入与能力说明见 https://github.com/gycrosskit/diagnostics 。

许可证：Apache-2.0。

## 初始化与隐私同意

候选固定 Common `1.2.0` / analytics `1.2.12` / APM `1.5.0`，在 `core.Uma.bindContext` 中只注册 `InternalPlugin + ApmPlugin`，再等待 `core.Uma.agree()`。不用会自动加入 `SourcePlugin` 的 `analytics.preInit`。

宿主通过 `DiagnosticSdkRuntime.initialize`（Kuikly 使用同一 Runtime 的 Module）调用；不要直接调用适配器绕过门禁。`privacyGranted=true` 必须表示用户已同意实际性能监控及启动/会话统计用途，旧的纯崩溃授权不能自动沿用。未同意或配置不符时 Runtime 不创建插件，同配置页面重接共享进程初始化，失败不自动重试。

InternalPlugin 会发送启动/会话统计及身份、应用上下文，并非无统计的 UMID 初始化器。SDK 清单和隐私政策须披露这项用途及实际字段；`disableOAID=true` 不关闭 AAID、ODID、UMID 或会话统计。组件不请求 OAID 权限，关闭调试日志、卡顿 HiLog 和剩余磁盘采样；不提供鸿蒙远程日志回捞或独立性能接口。初始化后的撤回不具备组件级停采保证，不能把再次传入 `false` 当成卸载已注册观察器。

Common `1.2.0` 仍要求应用资源 `rawfile/umconfig.json` 存在，可仅为 `{}`，AppKey/channel 继续从私有参数传入。完整接入、披露事项与撤回边界见[可选诊断 SDK](https://github.com/gycrosskit/diagnostics/blob/main/docs/可选诊断SDK.md#鸿蒙-common-与-anr)。

## ANR 与验收边界

2026-10-21 查询[Common 官方 Registry](https://ohpm.openharmony.cn/ohpm/@umeng/common)最新为 `1.2.0`，没有 `1.2.2`；后台 ANR“Common ≥1.2.2”提示与公开版本冲突。独立包使用上述三包组合已取得真实 native/ANR 入库及包名筛选证据，但这不代表本轮未发布 HAR 或业务宿主完成验收。

`enableJSCatch=false` 不关闭 APP_FREEZE watcher。插件/worker 异步初始化、系统故障附件、远端开关和采样均影响递送；`agree()` 返回及 HTTP200 都不等于后台入库。APM-only 保留旧 UMID 后能入库，不证明首次安装身份、比率分母或告警可靠，不作为正式无统计方案。
