// 运行真实 ArkTS Runtime/Module/适配源码；只替换平台和供应商，不上传任何数据。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require(process.env.TYPESCRIPT_PATH || '/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor-ohos-plugin/node_modules/typescript');
const root = path.resolve(__dirname, '..');
const sdkDir = path.join(root, 'ohos-sdk/core/src/main/ets');

function fixture(provider = 'bugly', overrides = {}, options = {}) {
  const configuration = { provider, appId: 'client-app', appKey: 'client-key', channel: 'test',
    crashes: true, performance: false, remoteLogs: provider !== 'umeng', brandId: 'chonghe', environment: 'qa', ...overrides };
  const calls = [];
  const modules = new Map();
  let release;
  const initialized = options.delayed ? new Promise(resolve => { release = resolve; }) : Promise.resolve();
  class Builder { constructor() { calls.push(['builder']); } }
  class BaseModule { onDestroy() {} }
  const dummyModule = name => class { static MODULE_NAME = name; };
  const gyc = ['DebugTools', 'SystemActions', 'Permission', 'Location', 'Toast', 'Scanner', 'JVerification'];
  const vendor = {
    BuglyBuilder: Object.assign(Builder, { PLATFORM_PRO: 'PRO' }),
    Bugly: {
      init: (context, builder) => { calls.push(['bugly', context, builder]); return initialized; },
      setInitUIAbility: ability => calls.push(['ability', ability])
    },
    AppVersionMode: { DEBUG: 'Debug', GRAY: 'Gray', RELEASE: 'Release' },
    TDLogBuilder: class {}, TDLogMode: { Async: 0 }, LogLevel: { LEVEL_ALL: 0 },
    TDLog: { init: value => calls.push(['log', value]), getTDLogAdapter: () => ({}),
      e: (...args) => calls.push(['e', ...args]), w: (...args) => calls.push(['w', ...args]),
      d: (...args) => calls.push(['d', ...args]), i: (...args) => calls.push(['i', ...args]),
      flushLog: () => { if (options.lifecycleThrows) throw Error('vendor failure'); calls.push(['flush']); } },
    TDDiagBuilder: Object.assign(class {}, { PLATFORM_PRO: 3 }),
    TDDiag: { init: (...args) => calls.push(['diagnose', ...args]),
      setGuid: value => calls.push(['guid', value]), setDeviceId: value => calls.push(['device', value]),
      syncConfig: force => { if (options.lifecycleThrows) throw Error('vendor failure'); calls.push(['sync', force]); } }
  };
  const stubs = {
    '@kit.AbilityKit': { UIAbility: class {} },
    '@kit.CoreFileKit': { fileIo: { accessSync: () => false, mkdirSync: (...args) => calls.push(['mkdir', ...args]) } },
    '@kit.PerformanceAnalysisKit': { hilog: { warn: () => calls.push(['warn']), info() {} } },
    '@kuikly-open/render': { KuiklyRenderBaseModule: BaseModule, IKuiklyViewDelegate: class {}, KuiklyNativeManager: class {} },
    'BuildProfile': { default: { BRAND_ID: 'chonghe', BUILD_TYPE: 'QA' } },
    'bugly': vendor,
    '@kit.ArkWeb': { webview: { WebviewController: { setWebDebuggingAccess() {} } } },
    '@kit.ArkUI': {}, '@kit.LocalizationKit': {}, '@ohos.router': {}, 'libkuikly_napi.so': {},
    './webview/AppGYWebView': {},
    './platform/SystemActionsModule': { SystemActionsModule: dummyModule('SxmSystemActions') },
    './platform/DebugToolsModule': { DebugToolsModule: dummyModule('SxmDebugTools') },
    './sdk/auth/AuthModule': { makeAuthModule() {} }, './sdk/auth/AuthUiConfig': {},
    './sdk/wechat/WechatModule': { WechatModule: dummyModule('Wechat') },
    '../kuikly/sdk/wechat/WechatSdk': { wechatSdk: { handleWant: () => false } },
    '../kuikly/platform/ExternalLinkRelay': { externalLinkRelay: { submit() {} } },
    '@gycrosskit/system-actions-native': { GycSystemActionsModule: dummyModule('GycSystemActions'), SystemActions: class {} },
    '@gycrosskit/sound': { SoundModule: dummyModule('Sound') },
    '@gycrosskit/media': { MediaModule: dummyModule('Media') },
    '@umeng/common': { core: { Uma: {
      bindContext: config => calls.push(['preInit', config]),
      agree: () => { calls.push(['umeng']); return initialized; }
    } } },
    '@umeng/apm': { ApmPlugin: class { constructor(value) { calls.push(['apm-plugin', value]); } } }
  };
  for (const name of gyc) {
    const packageName = name.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase();
    const key = '@gycrosskit/' + packageName + '-native';
    if (!stubs[key]) stubs[key] = { ['Gyc' + name + 'Module']: dummyModule('Gyc' + name) };
  }
  function load(file) {
    if (modules.has(file)) return modules.get(file).exports;
    const module = { exports: {} };
    modules.set(file, module);
    const source = fs.readFileSync(file, 'utf8');
    const result = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2021,
      module: ts.ModuleKind.CommonJS } });
    const requireSource = name => {
      if (name.endsWith('/DiagnosticSdkFactory')) return {
        diagnosticSdkConfiguration: configuration,
        createDiagnosticSdk: () => {
          calls.push(['factory']);
          if (provider === 'none') return null;
          return load(path.join(root, 'ohos-sdk', provider, 'Index.ets')).createDiagnosticSdk();
        }
      };
      if (name === '@gycrosskit/diagnostics-core') return load(path.join(root, 'ohos-sdk/core/Index.ets'));
      if (name === './DiagnosticSdk') return {};
      if (stubs[name]) return stubs[name];
      if (name.startsWith('.')) return load(path.resolve(path.dirname(file), name + '.ets'));
      throw Error('Unexpected module: ' + name);
    };
    vm.runInNewContext('(function(require,module,exports) {' + result.outputText + '\n})',
      { Promise, Error, JSON, $r: () => ({ id: 1 }) })(requireSource, module, module.exports);
    return module.exports;
  }
  const context = { filesDir: '/private/app', resourceManager: { getStringSync: () => '' }, getApplicationContext: () => 'application' };
  const Runtime = load(path.join(sdkDir, 'DiagnosticSdkRuntime.ets')).DiagnosticSdkRuntime;
  const runtime = new Runtime(configuration, () => {
    calls.push(['factory']);
    if (provider === 'none') return null;
    return load(path.join(root, 'ohos-sdk', provider, 'Index.ets')).createDiagnosticSdk();
  }, 'chonghe', 'qa');
  return { configuration, calls, runtime, context, load, release,
    initialize: (consent = true) => runtime.initialize(context, consent, provider,
      configuration.brandId, configuration.environment, 'random-install-id', '2.3.4', '567') };
}

async function verify() {
  for (const setup of [fixture('none'), fixture('bugly', { appKey: '' }),
    fixture('bugly', { appId: '' }), fixture('bugly', { brandId: 'other' }),
    fixture('bugly', { environment: 'release' }), fixture('umeng', { remoteLogs: true }), fixture('bugly', { performance: true }),
    fixture('umeng', { performance: true }), fixture('bugly', { crashes: false, remoteLogs: false })]) {
    await assert.rejects(setup.initialize());
    assert.equal(setup.calls.length, 0, 'invalid configuration must not construct SDK');
  }
  const gated = fixture();
  gated.runtime.onAbilityCreated({ name: 'EntryAbility' });
  gated.runtime.onForeground(); gated.runtime.onBackground(); gated.runtime.writeLog('I', 'tag', 'before');
  await assert.rejects(gated.initialize(false));
  assert.equal(gated.calls.length, 0, 'privacy denial must not initialize or write vendor log');
  await gated.initialize();
  await gated.initialize();
  assert.equal(gated.calls.filter(call => call[0] === 'bugly').length, 1, 'process must initialize once');
  const builder = gated.calls.find(call => call[0] === 'bugly')[2];
  assert.equal(builder.platform, 'PRO');
  assert.equal(builder.debugMode, false);
  assert.equal(builder.enableJsCrashProtect, false);
  assert.equal(builder.appVersionMode, 'Gray');
  assert.equal(builder.deviceId, 'random-install-id');
  assert.deepEqual(Array.from(builder.enablePerfModules), []);
  assert.equal(gated.calls.find(call => call[0] === 'log')[1].maxFileSize, 5 * 1024 * 1024);
  assert(gated.calls.some(call => call[0] === 'guid' && call[1] === 'random-install-id'));
  assert(gated.calls.some(call => call[0] === 'device' && call[1] === 'random-install-id'));
  gated.runtime.writeLog('ERROR', 'tag', 'sanitized');
  gated.runtime.onForeground(); gated.runtime.onBackground();
  assert(gated.calls.some(call => call[0] === 'e' && call[2] === 'sanitized'));
  assert(gated.calls.some(call => call[0] === 'sync' && call[1] === false));
  assert(gated.calls.some(call => call[0] === 'flush'));

  const failedLifecycle = fixture('bugly', {}, { lifecycleThrows: true });
  await failedLifecycle.initialize();
  failedLifecycle.runtime.onForeground(); failedLifecycle.runtime.onBackground();
  assert.equal(failedLifecycle.calls.filter(call => call[0] === 'warn').length, 2);

  const crashOnly = fixture('bugly', { remoteLogs: false });
  await crashOnly.initialize();
  crashOnly.runtime.writeLog('I', 'tag', 'message');
  assert.equal(crashOnly.calls.filter(call => ['log', 'diagnose', 'i'].includes(call[0])).length, 0);
  const umeng = fixture('umeng');
  await umeng.initialize();
  await umeng.initialize();
  assert.equal(umeng.calls.filter(call => call[0] === 'umeng').length, 1);
  assert.equal(umeng.calls.find(call => call[0] === 'apm-plugin')[1].enableJSCatch, false);
  assert.equal(umeng.calls.find(call => call[0] === 'preInit')[1].disableOAID, true);
  assert.equal(umeng.calls.find(call => call[0] === 'preInit')[1].plugins.length, 1);
  assert.equal(umeng.calls.find(call => call[0] === 'apm-plugin')[1].disableDiskFreeSize, true);
  umeng.runtime.writeLog('E', 'tag', 'ordinary log');
  assert.equal(umeng.calls.filter(call => call[0] === 'umeng').length, 1, 'ordinary logs must not become custom crash reports');

  const delayed = fixture('bugly', {}, { delayed: true });
  const Module = delayed.load(path.join(sdkDir, 'DiagnosticSdkModule.ets')).DiagnosticSdkModule;
  const module = new Module(delayed.context, delayed.runtime);
  const input = JSON.stringify({ privacyGranted: true, provider: 'bugly', brandId: 'chonghe', environment: 'qa',
    deviceId: 'random-install-id', versionName: '2.3.4', buildNumber: '567' });
  let status;
  for (const invalid of ['null', '{', '{"privacyGranted":true,"deviceId":7}']) {
    module.call('initialize', invalid, value => { status = value.status; });
    assert.equal(status, 'invalid');
  }
  status = undefined;
  module.call('initialize', input, value => { status = value.status; });
  await Promise.resolve();
  assert.equal(status, undefined, 'must not acknowledge ready until native init resolves');
  delayed.runtime.writeLog('E', 'tag', 'before-ready');
  assert.equal(delayed.calls.filter(call => call[0] === 'e').length, 0);
  delayed.release();
  await delayed.initialize(); await Promise.resolve();
  assert.equal(status, 'ready');
  const json = JSON.parse(module.call('configuration', null, null));
  assert.equal(json.appKey, 'client-key');
  module.onDestroy();
  assert.equal(module.call('configuration', null, null), null);
  const reconnecting = fixture('bugly', {}, { delayed: true });
  const ReconnectModule = reconnecting.load(path.join(sdkDir, 'DiagnosticSdkModule.ets')).DiagnosticSdkModule;
  const oldPage = new ReconnectModule(reconnecting.context, reconnecting.runtime);
  const newPage = new ReconnectModule(reconnecting.context, reconnecting.runtime);
  let oldReceipt = false;
  let newReceipt;
  oldPage.call('initialize', input, () => { oldReceipt = true; });
  oldPage.onDestroy();
  newPage.call('initialize', input, value => { newReceipt = value.status; });
  reconnecting.release();
  await reconnecting.initialize(); await Promise.resolve();
  assert.equal(oldReceipt, false);
  assert.equal(newReceipt, 'ready');
  assert.equal(reconnecting.calls.filter(call => call[0] === 'bugly').length, 1, 'page replacement must reuse process initialization');
  for (const selected of ['bugly', 'umeng']) {
    const mismatch = fixture(selected);
    const wrong = selected === 'bugly' ? 'umeng' : 'bugly';
    const sdk = mismatch.load(path.join(root, 'ohos-sdk', wrong, 'Index.ets')).createDiagnosticSdk();
    await assert.rejects(sdk.initialize(mismatch.context, mismatch.configuration, 'id', '1', '2'),
      /diagnostic_sdk_provider_mismatch/);
    assert.equal(mismatch.calls.length, 0, 'mismatched factory must not touch vendor SDK');
  }
  const logsOnly = fixture('bugly', { crashes: false });
  await logsOnly.initialize();
  logsOnly.runtime.onAbilityCreated({});
  logsOnly.runtime.writeLog('E', 'tag', 'sanitized');
  assert.equal(logsOnly.calls.filter(call => call[0] === 'bugly' || call[0] === 'ability').length, 0);
  assert(logsOnly.calls.some(call => call[0] === 'diagnose'));
  assert(logsOnly.calls.some(call => call[0] === 'e'));
  console.log('OHOS diagnostics: none/config/privacy/once/await/crash/remote logs/lifecycle passed');
}
module.exports = { fixture, verify };
if (require.main === module) verify().catch(error => { console.error(error); process.exitCode = 1; });
