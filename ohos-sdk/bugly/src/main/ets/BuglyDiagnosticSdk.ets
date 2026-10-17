import { common, UIAbility } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { Bugly, BuglyBuilder, AppVersionMode, TDLog, TDLogBuilder, TDLogMode, TDDiag, TDDiagBuilder, LogLevel } from 'bugly';
import { DiagnosticSdk, DiagnosticSdkConfiguration } from '@gycrosskit/diagnostics-core';

/** Bugly 自己持有崩溃观察和远程捞取任务；宿主只交付已脱敏业务日志。 */
export class BuglyDiagnosticSdk implements DiagnosticSdk {
  private remoteLogs: boolean = false;
  private crashes: boolean = false;

  async initialize(context: common.UIAbilityContext, configuration: DiagnosticSdkConfiguration,
    deviceId: string, versionName: string, buildNumber: string): Promise<void> {
    if (configuration.performance) { throw new Error('diagnostic_sdk_performance_unsupported'); }
    if (configuration.crashes) {
      const builder = new BuglyBuilder();
      builder.appId = configuration.appId;
      builder.appKey = configuration.appKey;
      builder.deviceId = deviceId;
      builder.platform = BuglyBuilder.PLATFORM_PRO;
      builder.appVersion = versionName;
      builder.buildNum = buildNumber;
      builder.appChannel = configuration.channel;
      builder.appVersionMode = configuration.environment === 'debug' ? AppVersionMode.DEBUG :
        configuration.environment === 'qa' ? AppVersionMode.GRAY : AppVersionMode.RELEASE;
      builder.debugMode = false;
      builder.sdkLogMode = false;
      builder.enablePrivacyInfoAuthorized = true;
      builder.enableJsCrashProtect = false;
      builder.enablePerfModules = [];
      await Bugly.init(context.getApplicationContext(), builder);
      this.crashes = true;
    }
    if (!configuration.remoteLogs) { return; }
    const directory = context.filesDir + '/bugly-remote-logs';
    if (!fileIo.accessSync(directory)) { fileIo.mkdirSync(directory, true); }
    const log = new TDLogBuilder();
    log.logDir = directory;
    log.namePrefix = 'business';
    log.logLevel = LogLevel.LEVEL_ALL;
    log.mode = TDLogMode.Async;
    log.consoleLog = false;
    log.maxFileSize = 5 * 1024 * 1024;
    log.maxAliveTime = 5 * 24 * 60 * 60;
    TDLog.init(log);
    const diagnose = new TDDiagBuilder();
    diagnose.appId = configuration.appId;
    diagnose.appKey = configuration.appKey;
    diagnose.platform = TDDiagBuilder.PLATFORM_PRO;
    diagnose.appVersion = versionName;
    diagnose.loggerAdapter = TDLog.getTDLogAdapter();
    if (!diagnose.loggerAdapter) { throw new Error('bugly_log_adapter_unavailable'); }
    TDDiag.init(context.getApplicationContext(), diagnose);
    TDDiag.setGuid(deviceId);
    TDDiag.setDeviceId(deviceId);
    this.remoteLogs = true;
  }

  writeLog(level: string, tag: string, message: string): void {
    if (!this.remoteLogs) { return; }
    if (level === 'E' || level === 'ERROR') { TDLog.e(tag, message); }
    else if (level === 'W' || level === 'WARN') { TDLog.w(tag, message); }
    else if (level === 'D' || level === 'DEBUG') { TDLog.d(tag, message); }
    else { TDLog.i(tag, message); }
  }

  onAbilityCreated(ability: UIAbility): void { if (this.crashes) { Bugly.setInitUIAbility(ability); } }
  onForeground(): void { if (this.remoteLogs) { TDDiag.syncConfig(false); } }
  onBackground(): void { if (this.remoteLogs) { TDLog.flushLog(); } }
}
