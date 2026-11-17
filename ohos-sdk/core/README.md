# @gycrosskit/diagnostics-core

组件构建沿用 `@kuikly-open/render` 既有精确基线 `2.29.1`，避免安装时因范围解析升级 Renderer；厂商 SDK 与公开 API 不变。

当前包候选版本为 `1.0.7`，尚未发布；Harmony 仅使用受信私 OHPM，安装前由宿主将 `@gycrosskit` scope 配置到私仓。发布及精确安装状态以对应 Release/Registry 收据为准，历史 rc.20 记录保留。

提供 HarmonyOS 诊断配置、运行时及 Kuikly Module。

```bash
ohpm install @gycrosskit/diagnostics-core@1.0.7
```

由宿主显式选择供应商并传入配置，组件不依赖业务宿主。安装库不代表已完成设备、供应商服务或上报验收。

纯 ArkTS 实现，不打包额外的 Kotlin runtime。接入与能力说明见 https://github.com/gycrosskit/diagnostics 。

许可证：Apache-2.0。

构建要求：HarmonyOS 6.0.2 / API22，`targetSdkVersion` 与 `compatibleSdkVersion` 均为 `6.0.2(22)`；真实编译工具版本与源码摘要见对应 Release 的 HAR provenance。
