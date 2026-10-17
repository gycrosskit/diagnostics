# 可选诊断 SDK

已发布的 `0.2.0-rc.15` Maven 将 Bugly、友盟实现迁到独立远程组件。默认 `DiagnosticSdkProvider.NONE`；core 不传递任何供应商 SDK 依赖。两种供应商均先使用已有本地 diagnostics；未配置、品牌/环境不符、未获隐私授权或初始化抛错时不转发远程日志。

## 依赖与职责

| 平台 | Bugly 入口 | 友盟入口 |
| --- | --- | --- |
| Android Maven | `diagnostics-bugly` rc.15 | `diagnostics-umeng` rc.15 |
| iOS Git Pod | `GYDiagnosticsBugly` + `GYDiagnosticsSdk` 同仓 rc.13 | `GYDiagnosticsUmeng` + `GYDiagnosticsSdk` 同仓 rc.13 |
| OHOS Release HAR | `DiagnosticsCore.har` + `DiagnosticsBugly.har` | `DiagnosticsCore.har` + `DiagnosticsUmeng.har` |

iOS Git Pods 与 OHOS HAR 保持已冻结的 rc.13；rc.14/rc.15 只修 Maven 的 Android 依赖闭包，公共协议未变。友盟 APM 同样漏声明 OkHttp/Okio，故适配组件显式包含 OkHttp 5.2.1，不能依赖宿主刚好已有网络库。BuglyPro AAR 保留 SSE 类型引用但其 POM 漏声明，故 Android 适配组件补齐 OkHttp SSE 5.2.1；即使关闭性能 monitor，R8 仍需解析这些类型，不能在宿主用忽略规则代替真实依赖。

对外只发布 Bugly、友盟两个供应商适配组件；每个组件整体引入厂商 SDK，宿主默认选择 `none`，本期提供 `bugly` / `umeng`，不按崩溃/日志再拆包。能力开关控制初始化和日志转发，关闭开关不代表厂商二进制代码被剔除。

Android 宿主仅调用 core 的 `installedAndroidDiagnosticSdkBridge(context)`；组件通过标准 SPI 注册且提供混淆保留规则。iOS 不同供应商 Pod 均公开同一 `GYDiagnosticsProvider` 模块与工厂，一次只链接一个实现。OHOS HAR 均公开 `createDiagnosticSdk()`。因此换供应商只改依赖和构建参数，不修改业务日志、授权或生命周期流程。provider 使用开放的安全字符串，无需修改 core 枚举或宿主白名单；新供应商的必填项、SDK 版本和能力差异由组件维护。

iOS 厂商二进制含静态 XCFramework。使用默认动态 `use_frameworks!` 的宿主，须在已有 `pre_install` 中按 `product_module_name == 'GYDiagnosticsProvider'` 将对应 target 设为 `Pod::BuildType.static_framework`；公共 `GYDiagnosticsSdk` 无需一起改成静态。该条件按统一模块名处理，换供应商不改名单；不要新增第二个 hook 覆盖已有接线。

KMP 公共契约位于 `io.github.gycrosskit.diagnostics.sdk`；Android 具体适配器由所选模块自动提供，宿主不 import 供应商类型。iOS 三个新 Pod 都只包含 Swift，不依赖宿主 `Shared` framework；宿主保留跨语言薄桥。OHOS 三个 HAR 只含 ArkTS，不打入另一份 Kotlin runtime；`diagnostics-kuikly` 持有 Renderer 线程调度、页面销毁/重建与同一进程初始化结果的衔接。

## 通用配置

`DiagnosticSdkConfiguration` 不区分供应商类型；`appId` / `appKey` / `channel`、崩溃/日志/性能开关由适配器映射到各平台 SDK。
当前 [Bugly 专业版](https://bugly.tds.tencent.com/docs/sdk/android/)需要 App ID + App Key，
[友盟](https://devs.umeng.com/docs/193624/detail/194590)使用 App Key + channel。具体客户端值仍须按供应商与平台申请，不能因字段同名而互用。
宿主已有的品牌、构建类型、版本、build number 和授权后的安装标识作为运行上下文传入；组件不维护品牌清单或业务环境配置。
配置中的 `brandId` / `environment` 另表示凭据适用范围，须与真实上下文匹配，不应直接复制当前构建值来绕过校验。
`environment` 为 `debug` / `qa` / `release`；后端 test/production、推广渠道、厂商服务区域分别处理。
这些通用字段不保证厂商报表自动按品牌/环境分组：iOS native 适配不设置品牌/环境标签，OHOS 友盟没有覆写安装标识、版本/build 的公开入口。
core 的 Android runtime 显式提供同版 `kotlinx-coroutines-android`，确保极简宿主也能使用初始化入口的 Main dispatcher；不依赖宿主恰好引入 UI 协程。
需要严格隔离时应使用对应项目凭据；不复用 channel 充当品牌或环境，也不把统一接口说成各 SDK 元数据能力完全相同。
客户端配置不包含控制台 Token、符号上传密钥或服务端 API 凭据；新供应商所需参数由其适配组件维护。

配置来源与 SDK 依赖选择应分开：未来可由宿主现有配置接口/缓存提供同一份运行配置，接口只能启用包内已有且受支持的实现。
当前已发布版本只验收构建配置，不能宣称已支持接口下发。后续接入先采用进程启动快照，
分别验证配置范围、隐私准入和能力；初始化后更换凭据、厂商或关闭采集的语义取决于真实 SDK，不承诺热重配。

未发布的 OHOS 配置补丁扩展现有 `initialize` 请求：Kotlin 透传完整 `configuration`，原生 Module 校验类型、品牌、环境、安装 provider 和能力后冻结首次有效快照；同配置及安装身份的页面重接复用同一 Promise，异配置或身份明确拒绝。旧请求不带载荷时仍使用构建配置，失败 Promise 也不自动重试。
宿主打包选择须独立于运行配置；原生 Runtime 第五个可选参数 `installedProvider` 声明包内唯一实现，省略时沿用构建配置的 provider，兼容现有四参调用。缺客户端参数时可保留显式选中的 SDK 而以 `none` 运行；接入配置接口前升级到包含此补丁的新 Maven/HAR，并传入真实安装选择。补丁源码提交 `349276c` 已通过[远程源码门禁](https://github.com/gycrosskit/diagnostics/actions/runs/39010121240)，三个 HAR 已由真实 Hvigor 独立重新构建并校验源码 API 与摘要；补丁尚未发布，不以 rc.15 / 原生 rc.13 的旧回执代替此验证，也不将编译与替身测试当作设备或真实接口下发验收。
`configure` 只接受首次调用；接口/缓存必须先解析为确定快照，不能先用 `none` 占位再等待网络重复配置。

## 最小进程接线

```kotlin
// configuration 由构建配置提供；none 是默认值。
processScope.launch {
    DiagnosticSdkRuntime.configure(configuration, brandId, buildType, versionName, buildNumber, installedBridgeFactory) {
        awaitPrivacyAuthorization()
        if (isPrivacyAuthorized()) stableInstallId() else null
    }
}
// 复用现有 Logger，先本地落盘，再转发远程副本。
DiagnosticSdkRuntime.writeLog("ERROR", "LiveEntry", safeBusinessMessage)
```

授权与安装标识属于宿主；回调必须等待持久化隐私同意，并在生成/读取身份后再次核对授权。使用进程作用域，不让临时页面取消初始化。SDK 只初始化一次，失败不自动循环重试；`INITIALIZED` 只代表初始化 API 已结束，不代表控制台接收成功。不要在宿主再建一套日志落盘/上传队列。日志会过滤已知凭据并限制 4096 UTF-8 字节，队列最多 64 条，拥塞丢旧远程副本；语义脱敏仍由日志调用方负责。

Android 启用第三方崩溃时先交出本地实时 handler，明确抛错恢复原 handler；仅远程日志则保留本地 crash。iOS native `beforeStart` 仅在第三方 crashes=true 时停止现有原生 collector。OHOS Ability 前后台/销毁转发到进程 runtime，页面只注册 Module，不能拥有 SDK 初始化 Promise。

## 能力边界

| 能力 | Android | iOS | OHOS |
| --- | --- | --- | --- |
| Bugly 崩溃 | Java / Native / ANR | CrashMonitor | 厂商原生崩溃采集，API 22+ |
| Bugly 远程回捞 | TDOS；可仅日志 | TDOS；可仅日志 | TDLog / TDDiag；可仅日志 |
| 友盟崩溃 | Java / Native / ANR | crash 与 block 同一开关 | APM 崩溃能力 |
| 友盟远程回捞 | ULog / U-APM；可仅日志 | UAPMLog；可仅日志 | 公开 SDK 无接口，明确 Unsupported |
| 性能分析 | 本期预留，performance=true 明确拒绝 | 同左 | 同左；友盟内部仍有不可独立关闭的 CPU/内存崩溃上下文采样 |

`crashes` 默认 true、`remoteLogs` 默认 true、`performance` 默认 false；provider 默认 none，因此三个默认字段不会触发 SDK。所有能力 false 不初始化。按需引入是在构建时选择依赖并在授权后启用；不提供安装后动态下载 SDK 或随意卸载进程 crash handler。

供应商回捞缓存与本地待上传批次独立，不读取或确认删除本地 pending 原件。iOS TDOS 使用固定私有日志目录的白名单 packer；Android TDOS / OHOS TDDiag 没有公开的指定路径文件过滤接口，控制台还具备指定路径取文件能力，必须限制控制台权限。缓存额度来自厂商：Android Bugly 5 MiB / 文件、1 天、最小总量 200 MiB；iOS TDOS 10 天 / 50 MiB。友盟按其 SDK / 控制台策略管理。

## 验证入口

源码用现有 `regression.yml`：core 并发/授权/队列/脱敏测试，两个 Android 适配模块行为测试与 none/Bugly/友盟三组真实 R8 消费，真实 Native 编译与独立 Pod lint，OHOS Runtime/Module 和 Renderer 生命周期测试。冻结归档与公开 Maven 全量校验后，由 `verification-consumer` 仅消费精确发布坐标；宿主另验证 none 和选中供应商工厂。

HAR 只接受真实 Hvigor `assembleHar` 输出并校验源码、版本、API22 和依赖；源码 ZIP 不算 HAR。OHPM 未审核上架前使用不可变 Release HAR，不能宣称 Registry 可用。源码测试、SDK 编译、远程消费、设备启动和真实后台回捞分别记录；无配置时不运行真实 SDK 上报。
