# @gycrosskit/diagnostics-umeng

提供可选的友盟诊断适配器，依赖同版本 diagnostics-core。

```bash
ohpm install @gycrosskit/diagnostics-umeng@0.2.0-rc.16
```

由宿主显式选择供应商并传入配置，组件不依赖业务宿主。安装库不代表已完成设备、供应商服务或上报验收。

纯 ArkTS 实现，不打包额外的 Kotlin runtime。接入与能力说明见 https://github.com/gycrosskit/diagnostics 。

许可证：Apache-2.0。

## Common 版本与 ANR 边界

当前固定 Common `1.2.0` / APM `1.5.0`。[官方 Harmony 集成页](https://devs.umeng.com/docs/193624/detail/2835970)要求 Common ≥ `1.1.1`；2026-10-20 查询[Common 官方 Registry](https://ohpm.openharmony.cn/ohpm/@umeng/common)没有 `1.2.2`，后台 ANR 的同名版本提示与公开包信息冲突，不能据此升级或加入 analytics。
仅注册 `ApmPlugin` 的静态链路可到 `hiAppEvent` 的 `APP_FREEZE` watcher，`enableJSCatch=false` 不截断此链路；插件/worker 异步初始化及远端开关、采样意味着 `agree()` 返回不代表 ANR 收数成功。完整接入见[可选诊断 SDK](https://github.com/gycrosskit/diagnostics/blob/main/docs/可选诊断SDK.md)，真实设备验收另行判断。
