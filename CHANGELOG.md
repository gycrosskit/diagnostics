# 更新记录

## 1.0.0（未发布候选）

- Maven、四个 Git Pods、三个 HAR 及 Gradle consumer 默认版本对齐 `1.0.0`；HAR 适配器精确依赖同版 core。
- 基于当前远端 main `8f209be3068f3431f5e2f4c0998c10ee3e5d55fd`，保留 rc.19 / rc.20 的全部源码、许可、厂商版本及隐私合同。
- 构建、回归、归档与发布消费以 Jenkins 为迁移目标，待本轮实跑；历史验收不代算 1.0.0 发布或设备验收。

## 0.2.0-rc.10（预发布）

- Android ANR 按事件时间保留最新10份，过旧输入不替换新报告；补真实文件回归。
- CI 分离源码、main轻量门禁和精确Release消费者；Swift原生采集仍沿用0.2.0-rc.1。

## 0.2.0-rc.5（预发布）

- OHOS可选通知模块增加真实Curl transport与RFC2104 HMAC，复用已有SHA-256，维持API12基线。
- 响应限制按16KiB字节执行，errcode拒绝字符串；读取拒绝负快照大小。
- core/通知JVM、Android、iOS定向测试及OHOS/staging最终消费通过；14模块归档与源码SHA核对通过。
- 新不可变标签/Release/JitPack和干净远程消费分别核验，设备递送未验；详见[完整审查](docs/完整审查.md)。
