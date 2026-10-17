import { common, UIAbility } from '@kit.AbilityKit';
import { core } from '@umeng/common';
import { ApmPlugin } from '@umeng/apm';
import { DiagnosticSdk, DiagnosticSdkConfiguration } from '@gycrosskit/diagnostics-core';

/** 鸿蒙 U-APM 无公开日志回捞 API；不能把主动异常上报冒充回捞。 */
export class UmengDiagnosticSdk implements DiagnosticSdk {
  async initialize(context: common.UIAbilityContext, configuration: DiagnosticSdkConfiguration,
    _deviceId: string, _versionName: string, _buildNumber: string): Promise<void> {
    if (configuration.performance) { throw new Error('diagnostic_sdk_performance_unsupported'); }
    if (configuration.remoteLogs) { throw new Error('umeng_ohos_remote_logs_unsupported'); }
    if (!configuration.crashes) { return; }
    // analytics.preInit 会强制加入 SourcePlugin；用 common 公共入口只注册 APM 插件。
    // SDK 自带 CPU/内存等崩溃上下文采样不可关闭，本期不接独立性能分析接口。
    core.Uma.bindContext({ context: context.getApplicationContext(), appKey: configuration.appKey,
      channel: configuration.channel, enableLog: false, disableOAID: true,
      plugins: [new ApmPlugin({ enableJSCatch: false, collectBlockHiLog: false, disableDiskFreeSize: true })] });
    await core.Uma.agree();
  }

  writeLog(_level: string, _tag: string, _message: string): void {}
  onAbilityCreated(_ability: UIAbility): void {}
  onForeground(): void {}
  onBackground(): void {}
}
