import { common, UIAbility } from '@kit.AbilityKit';

/** SDK 标识只在私有 Module 接线读取，不进入 pageData 或日志。 */
export interface DiagnosticSdkConfiguration {
  provider: string;
  appId: string;
  appKey: string;
  channel: string;
  crashes: boolean;
  performance: boolean;
  remoteLogs: boolean;
  brandId: string;
  environment: string;
}

export interface DiagnosticSdk {
  initialize(context: common.UIAbilityContext, configuration: DiagnosticSdkConfiguration,
    deviceId: string, versionName: string, buildNumber: string): Promise<void>;
  writeLog(level: string, tag: string, message: string): void;
  onForeground(): void;
  onBackground(): void;
  onAbilityCreated(ability: UIAbility): void;
}
