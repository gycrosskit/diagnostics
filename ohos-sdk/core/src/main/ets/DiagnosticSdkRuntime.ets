import { common, UIAbility } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { DiagnosticSdk, DiagnosticSdkConfiguration } from './DiagnosticSdk';

/** 进程只初始化一个供应商；页面 Module 的重建不能重复安装异常观察器。 */
export class DiagnosticSdkRuntime {
  private sdk: DiagnosticSdk | null = null;
  private initialization: Promise<void> | null = null;
  private ready: boolean = false;
  private ability: UIAbility | null = null;

  private readonly selected: DiagnosticSdkConfiguration;
  private readonly factory: () => DiagnosticSdk | null;
  private readonly brandId: string;
  private readonly environment: string;

  constructor(configuration: DiagnosticSdkConfiguration, factory: () => DiagnosticSdk | null,
    brandId: string, environment: string) {
    this.selected = configuration;
    this.factory = factory;
    this.brandId = brandId;
    this.environment = environment;
  }

  configuration(): string { return JSON.stringify(this.selected); }

  initialize(context: common.UIAbilityContext, privacyGranted: boolean, provider: string,
    brandId: string, environment: string, deviceId: string, versionName: string, buildNumber: string): Promise<void> {
    const configuration = this.selected;
    if (!privacyGranted || configuration.provider === 'none' || provider !== configuration.provider ||
      brandId !== configuration.brandId || environment !== configuration.environment ||
      brandId !== this.brandId || environment !== this.environment ||
      !configuration.appKey.trim() || !deviceId.trim() || !versionName.trim() ||
      (provider === 'bugly' && !configuration.appId.trim())) {
      return Promise.reject(new Error('diagnostic_sdk_unavailable'));
    }
    if (configuration.performance) {
      return Promise.reject(new Error('diagnostic_sdk_performance_unsupported'));
    }
    if (!configuration.crashes && !configuration.remoteLogs) {
      return Promise.reject(new Error('diagnostic_sdk_unavailable'));
    }
    if (provider === 'umeng' && configuration.remoteLogs) {
      return Promise.reject(new Error('umeng_ohos_remote_logs_unsupported'));
    }
    if (this.initialization) { return this.initialization; }
    const sdk = this.factory();
    if (!sdk) { return Promise.reject(new Error('diagnostic_sdk_unavailable')); }
    this.sdk = sdk;
    // 失败保留同一 Promise，避免部分成功后重试重新安装原生崩溃采集。
    this.initialization = sdk.initialize(context, configuration, deviceId, versionName, buildNumber).then(() => {
      if (this.ability) { sdk.onAbilityCreated(this.ability); }
      this.ready = true;
    });
    return this.initialization;
  }

  writeLog(level: string, tag: string, message: string): void {
    if (this.ready) { this.sdk?.writeLog(level, tag, message); }
  }

  onAbilityCreated(ability: UIAbility): void {
    this.ability = ability;
    try { if (this.ready) { this.sdk?.onAbilityCreated(ability); } }
    catch (_) { hilog.warn(0x30, 'DiagnosticSdk', '%{public}s', 'SDK ability update failed'); }
  }

  onAbilityDestroyed(ability: UIAbility): void {
    if (this.ability === ability) { this.ability = null; }
  }

  onForeground(): void {
    try { if (this.ready) { this.sdk?.onForeground(); } }
    catch (_) { hilog.warn(0x30, 'DiagnosticSdk', '%{public}s', 'SDK config refresh failed'); }
  }

  onBackground(): void {
    try { if (this.ready) { this.sdk?.onBackground(); } }
    catch (_) { hilog.warn(0x30, 'DiagnosticSdk', '%{public}s', 'SDK flush failed; local log retained'); }
  }
}

