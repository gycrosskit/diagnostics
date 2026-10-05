# 更新记录

## 0.2.0-rc.5（候选）

- OHOS可选通知模块增加真实Curl transport与RFC2104 HMAC，复用已有SHA-256，维持API12基线。
- 响应限制按16KiB字节执行，errcode拒绝字符串；读取拒绝负快照大小。
- core/通知JVM、Android、iOS定向测试及OHOS/staging最终消费通过；14模块归档与源码SHA核对通过。
- 新不可变标签/Release/JitPack和干净远程消费分别核验，设备递送未验；详见[完整审查](docs/完整审查.md)。
