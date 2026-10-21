# 更新记录

## 未发布

- 固定 analytics `1.2.12`，通过 Common 公共入口注册 `InternalPlugin + ApmPlugin`，避免 `analytics.preInit` 自动附带的 `SourcePlugin`。
- 沿用 Runtime 隐私准入和进程单次初始化；授权须覆盖性能监控与启动/会话统计，补齐拒绝、重复调用及失败回归。
- 补充数据用途、资源前提和 ANR 验收边界；不覆盖已发布 rc.16 HAR，也不改业务宿主或后台。

## 0.2.0-rc.16

- 补齐官方 OHPM 发布说明，沿用同版本已验证的 HarmonyOS 纯 ArkTS 实现。
- 保持公开 API、依赖版本和运行时行为不变。
