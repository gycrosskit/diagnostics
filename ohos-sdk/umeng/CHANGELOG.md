# 更新记录

## 0.2.0-rc.20

- 将仓库原 Apache-2.0 LICENSE 文本纳入真实 HAR，补齐 OHPM 开源包检查。
- Maven / iOS 保持 rc.19；本版仅修正 HAR 许可打包，API、供应商依赖与授权行为不变。

## 0.2.0-rc.19

- 固定 analytics `1.2.12`，通过 Common 公共入口注册 `InternalPlugin + ApmPlugin`，避免 `analytics.preInit` 自动附带的 `SourcePlugin`。
- 沿用 Runtime 隐私准入和进程单次初始化；授权须覆盖性能监控与启动/会话统计，补齐拒绝、重复调用及失败回归。
- 补充数据用途、资源前提和 ANR 验收边界；不覆盖已发布 rc.16 HAR，也不改业务宿主或后台。
- 使用 HarmonyOS 6.0.2 / API22 SDK 构建，工程 target/model 与真实工具链对齐。

## 0.2.0-rc.16

- 补齐官方 OHPM 发布说明，沿用同版本已验证的 HarmonyOS 纯 ArkTS 实现。
- 保持公开 API、依赖版本和运行时行为不变。
