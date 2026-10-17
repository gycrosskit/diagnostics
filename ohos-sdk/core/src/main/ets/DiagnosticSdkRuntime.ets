import { common, UIAbility } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { DiagnosticSdk, DiagnosticSdkConfiguration } from './DiagnosticSdk';

/** 进程只初始化一个供应商；页面 Module 的重建不能重复安装异常观察器。 */
export class DiagnosticSdkRuntime {
  private sdk: DiagnosticSdk | null = null;
  private initialization: Promise<void> | null = null;
  private initializationKey: string = '';
  private ready: boolean = false;
  private ability: UIAbility | null = null;

  private readonly selected: DiagnosticSdkConfiguration;
  private readonly factory: () => DiagnosticSdk | null;
  private readonly brandId: string;
  private readonly environment: string;
  private readonly installedProvider: string;

  constructor(configuration: DiagnosticSdkConfiguration, factory: () => DiagnosticSdk | null,
    brandId: string, environment: string, installedProvider: string = configuration.provider) {
    this.selected = configuration;
    this.factory = factory;
    this.brandId = brandId;
    this.environment = environment;
    this.installedProvider = installedProvider;
  }

  configuration(): string { return JSON.stringify(this.selected); }

  initialize(context: common.UIAbilityContext, privacyGranted: boolean, provider: string,
    brandId: string, environment: string, deviceId: string, versionName: string, buildNumber: string,
    runtimeConfiguration?: DiagnosticSdkConfiguration): Promise<void> {
    // 旧 Module 沿用构建配置；新调用可注入接口快照，但不能切换未打包的实现。
    const candidate = runtimeConfiguration === undefined ? this.selected : runtimeConfiguration;
    if (!candidate || typeof candidate.provider !== 'string' || typeof candidate.appId !== 'string' ||
      typeof candidate.appKey !== 'string' || typeof candidate.channel !== 'string' ||
      typeof candidate.brandId !== 'string' || typeof candidate.environment !== 'string' ||
      typeof candidate.crashes !== 'boolean' || typeof candidate.remoteLogs !== 'boolean' ||
      typeof candidate.performance !== 'boolean') {
      return Promise.reject(new Error('diagnostic_sdk_invalid_configuration'));
    }
    const configuration: DiagnosticSdkConfiguration = {
      provider: candidate.provider, appId: candidate.appId, appKey: candidate.appKey, channel: candidate.channel,
      crashes: candidate.crashes, remoteLogs: candidate.remoteLogs, performance: candidate.performance,
      brandId: candidate.brandId, environment: candidate.environment
    };
    if (!privacyGranted || configuration.provider === 'none' || provider !== configuration.provider ||
      provider !== this.installedProvider || !/^[a-z][a-z0-9-]{0,63}$/.test(provider) ||
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
    const key = JSON.stringify([configuration, deviceId, versionName, buildNumber]);
    if (this.initialization) {
      return key === this.initializationKey ? this.initialization :
        Promise.reject(new Error('diagnostic_sdk_reconfiguration_unsupported'));
    }
    this.initializationKey = key;
    // 首个有效配置冻结；同步抛错也保留失败 Promise，页面重接不能重复安装采集器。
    this.initialization = Promise.resolve().then(() => {
      const sdk = this.factory();
      if (!sdk) { throw new Error('diagnostic_sdk_unavailable'); }
      this.sdk = sdk;
      return sdk.initialize(context, configuration, deviceId, versionName, buildNumber);
    }).then(() => {
      if (this.ability) { this.sdk?.onAbilityCreated(this.ability); }
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
