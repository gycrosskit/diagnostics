# @gycrosskit/diagnostics-bugly

当前包版本为 `1.0.3`；发布及精确安装状态以对应 Release/Registry 收据为准，历史 rc.20 记录保留。

提供可选的 Bugly 诊断适配器，依赖同版本 diagnostics-core。

```bash
ohpm install @gycrosskit/diagnostics-bugly@1.0.3
```

由宿主显式选择供应商并传入配置，组件不依赖业务宿主。安装库不代表已完成设备、供应商服务或上报验收。

纯 ArkTS 实现，不打包额外的 Kotlin runtime。接入与能力说明见 https://github.com/gycrosskit/diagnostics 。

许可证：Apache-2.0。

构建要求：HarmonyOS 6.0.2 / API22，`targetSdkVersion` 与 `compatibleSdkVersion` 均为 `6.0.2(22)`；真实编译工具版本与源码摘要见对应 Release 的 HAR provenance。
