import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { DiagnosticSdkRuntime } from './DiagnosticSdkRuntime';

interface DiagnosticSdkRequest {
  privacyGranted?: boolean;
  provider?: string;
  brandId?: string;
  environment?: string;
  deviceId?: string;
  versionName?: string;
  buildNumber?: string;
  level?: string;
  tag?: string;
  message?: string;
}

/** 只转换私有 SDK 调用；供应商和进程生命周期由 Runtime 持有。 */
export class DiagnosticSdkModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'GycDiagnosticSdk';
  private destroyed: boolean = false;
  private context: common.UIAbilityContext;

  private readonly runtime: DiagnosticSdkRuntime;
  constructor(context: common.UIAbilityContext, runtime: DiagnosticSdkRuntime) {
    super(); this.context = context; this.runtime = runtime;
  }
  syncMode(): boolean { return false; }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.destroyed) { return null; }
    if (method === 'configuration') { return this.runtime.configuration(); }
    let input: DiagnosticSdkRequest;
    try { input = JSON.parse(typeof params === 'string' ? params : '{}') as DiagnosticSdkRequest; }
    catch (_) { callback?.({ status: 'invalid' }); return null; }
    if (!input || typeof input !== 'object') { callback?.({ status: 'invalid' }); return null; }
    if (method === 'initialize') {
      if (typeof input.provider !== 'string' || typeof input.brandId !== 'string' ||
        typeof input.environment !== 'string' || typeof input.deviceId !== 'string' ||
        typeof input.versionName !== 'string' || typeof input.buildNumber !== 'string') {
        callback?.({ status: 'invalid' }); return null;
      }
      this.runtime.initialize(this.context, input.privacyGranted === true, input.provider ?? '',
        input.brandId ?? '', input.environment ?? '', input.deviceId ?? '', input.versionName ?? '', input.buildNumber ?? '')
        .then(() => { if (!this.destroyed) { callback?.({ status: 'ready' }); } })
        .catch(() => { if (!this.destroyed) { callback?.({ status: 'unavailable' }); } });
      return null;
    }
    if (method === 'writeLog' && typeof input.level === 'string' &&
      typeof input.tag === 'string' && typeof input.message === 'string') {
      try { this.runtime.writeLog(input.level ?? '', input.tag ?? '', input.message ?? ''); }
      catch (_) { hilog.warn(0x30, 'DiagnosticSdk', '%{public}s', 'SDK log write failed; local log retained'); }
    }
    return null;
  }

  onDestroy(): void { this.destroyed = true; super.onDestroy(); }
}
