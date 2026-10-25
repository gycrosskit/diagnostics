# @gycrosskit/diagnostics-core

`1.0.1` 为未发布候选，下方安装命令仅在 Registry 发布并完成精确消费验收后使用；历史 rc.20 记录保留。构建与发布迁移目标为 Jenkins，待本轮实跑验收。

提供 HarmonyOS 诊断配置、运行时及 Kuikly Module。

```bash
ohpm install @gycrosskit/diagnostics-core@1.0.1
```

由宿主显式选择供应商并传入配置，组件不依赖业务宿主。安装库不代表已完成设备、供应商服务或上报验收。

纯 ArkTS 实现，不打包额外的 Kotlin runtime。接入与能力说明见 https://github.com/gycrosskit/diagnostics 。

许可证：Apache-2.0。

构建要求：HarmonyOS 6.0.2 / API22，`targetSdkVersion` 与 `compatibleSdkVersion` 均为 `6.0.2(22)`；真实编译工具版本与源码摘要见对应 Release 的 HAR provenance。
