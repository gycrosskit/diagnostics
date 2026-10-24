#!/usr/bin/env python3
"""缓存只用于源码编译；测试任务与发布验证保留原执行边界。"""
from pathlib import Path
import re
import json
import os
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[2]
workflow = (ROOT / '.github/workflows/regression.yml').read_text()
jobs = dict(re.findall(r'^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)',
                       workflow.split('\njobs:\n', 1)[1], re.M | re.S))
for name in ('android', 'native'):
    job = jobs[name]
    assert "GYC_CI_BUILD_CACHE: 'true'" in job
    assert 'gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a' in job
    assert "cache-read-only: ${{ github.event_name == 'pull_request' }}" in job
    cache_step = re.search(r'uses: gradle/actions/setup-gradle@[^\n]+\n        if: ([^\n]+)', job)
    assert cache_step and cache_step.group(1) == "needs.changes.outputs.source == 'true'"
    assert 'cache: gradle' not in job, 'Do not restore Gradle User Home twice'
    assert '--init-script "$PWD/scripts/ci-repositories.gradle"' in job
for name, job in jobs.items():
    if name not in ('android', 'native'):
        assert 'GYC_CI_BUILD_CACHE' not in job
        assert 'gradle/actions/setup-gradle@' not in job
policy = (ROOT / 'scripts/ci-repositories.gradle').read_text()
assert "if (System.getenv('GYC_CI_BUILD_CACHE') == 'true')" in policy
assert 'gradle.startParameter.buildCacheEnabled = true' in policy
assert 'withType(org.gradle.api.tasks.testing.AbstractTestTask).configureEach' in policy
assert 'outputs.upToDateWhen { false }' in policy
assert "outputs.doNotCacheIf('CI must execute behavior tests') { true }" in policy
archive = (ROOT / 'scripts/archive-prebuilt.sh').read_text()
assert '--init-script "$PWD/scripts/ci-repositories.gradle"' in archive
print('CI cache contract: source-only cache and real test execution policy')

# 执行真实 R8 shell 步骤，用记录器替代编译命令，验证同 SHA 共享坐标、不同 SHA 隔离。
r8_step = jobs['native'].split('      - name: Verify Android R8 from source staging publications\n', 1)[1].split('      - name:', 1)[0]
commands = r8_step.split('        run: |\n', 1)[1]
commands = '\n'.join(line[10:] for line in commands.splitlines())
with tempfile.TemporaryDirectory() as directory:
    temporary = Path(directory)
    recorder = temporary / 'python3'
    recorder.write_text('#!' + sys.executable + '\n' + """import json, os, sys
with open(os.environ["R8_CALLS"], "a") as output:
    output.write(json.dumps({"version": os.environ.get("VERSION"), "args": sys.argv[1:]}) + "\\n")
provider = os.environ.get("R8_FAIL_PROVIDER")
if provider and "-PdiagnosticsProvider=" + provider in sys.argv:
    sys.exit(17)
""")
    recorder.chmod(0o700)
    for sha in ('a' * 40, 'b' * 40):
        calls = temporary / (sha + '.jsonl')
        subprocess.run(['bash', '-euo', 'pipefail', '-c', commands], check=True, cwd=ROOT,
                       env={**os.environ, 'PATH': directory + os.pathsep + os.environ['PATH'],
                            'GITHUB_SHA': sha, 'R8_CALLS': str(calls)})
        invocations = [json.loads(line) for line in calls.read_text().splitlines()]
        assert len(invocations) == 4
        assert invocations[0]['version'] == 'source-r8-' + sha
        for invocation, provider in zip(invocations[1:], ('none', 'bugly', 'umeng')):
            assert '-PdiagnosticsVersion=source-r8-' + sha in invocation['args']
            assert '-PdiagnosticsProvider=' + provider in invocation['args']
            assert ':r8-app:minifyReleaseWithR8' in invocation['args']
            assert ':r8-app:verifyR8Release' in invocation['args']
            assert '--refresh-dependencies' not in invocation['args']
    failed_calls = temporary / 'failed.jsonl'
    failed = subprocess.run(['bash', '-euo', 'pipefail', '-c', commands], cwd=ROOT,
                            env={**os.environ, 'PATH': directory + os.pathsep + os.environ['PATH'],
                                 'GITHUB_SHA': 'c' * 40, 'R8_CALLS': str(failed_calls), 'R8_FAIL_PROVIDER': 'bugly'})
    assert failed.returncode == 17
    attempted = [json.loads(line) for line in failed_calls.read_text().splitlines()]
    assert len(attempted) == 3, 'Failed consumer must stop without retry or continuing to Umeng'
print('Source R8 contract: per-SHA publication identity and three consumers without repeated refresh')
assert 'Resolve source metadata with bounded connection recovery' not in jobs['android']
assert 'Verify Android R8 from source staging publications' not in jobs['android']
assert jobs['native'].index(':diagnostics-core:compileKotlinIosArm64') < jobs['native'].index('Resolve source metadata with bounded connection recovery')
assert 'test -f "$ANDROID_HOME/platforms/android-36/android.jar"' in jobs['native']
assert jobs['native'].index('pod --check-only') < jobs['native'].index(':diagnostics-core:compileKotlinIosArm64')
assert 'node --test verification/ohos-sdk.cjs' in jobs['android']
assert 'testDebugUnitTest' in jobs['android'] and 'assert-test-results.py' in jobs['android']


# 只恢复 metadata 下载连接，不把编译失败或旧日志中的超时当成重试依据。
resolve_step = jobs['native'].split('      - name: Resolve source metadata with bounded connection recovery\n', 1)[1].split('      - name:', 1)[0]
resolve_commands = resolve_step.split('        run: |\n', 1)[1]
resolve_commands = '\n'.join(line[10:] for line in resolve_commands.splitlines())
with tempfile.TemporaryDirectory() as directory:
    temporary = Path(directory)
    recorder = temporary / 'python3'
    recorder.write_text('#!' + sys.executable + '\n' + r'''import json, os, sys
from pathlib import Path
args = sys.argv[1:]
if args[0].endswith('/ci-metadata-retry.py'):
    os.execv(sys.executable, [sys.executable, *args])
calls = Path(os.environ['RESOLVE_CALLS'])
with calls.open('a') as output:
    output.write(json.dumps({'version': os.environ.get('VERSION'), 'args': args}) + '\n')
attempt = len(calls.read_text().splitlines())
case = os.environ['RESOLVE_CASE']
log = Path(args[args.index('--log') + 1]); log.parent.mkdir(parents=True, exist_ok=True)
if case == 'success' or (case == 'once' and attempt > 1) or (case == 'twice' and attempt > 2):
    log.write_text('BUILD SUCCESSFUL\n'); sys.exit(0)
host = 'other.example' if case == 'wrong_host' else 'maven.eazytec-cloud.com'
timeout = '> Connect to ' + host + ':443 [61.177.127.227] failed: Connect timed out\n'
url = 'https://' + host + '/nexus/repository/maven-public/org/example/metadata.jar'
task = ':diagnostics-core:compileKotlinAndroid' if case == 'compiler' else ':diagnostics-core:transformCommonMainDependenciesMetadata'
failure = "Execution failed for task '" + task + "'.\n> Could not resolve all files.\n   > Could not GET '" + url + "'.\n      "
failure += {'tls': '> PKIX path building failed\n', 'http': '> Received status code 404\n',
            'stale': '> Compilation error\n'}.get(case, timeout)
if case == 'mixed': failure += '> Compilation error\n'
if case == 'mixed_missing': failure += '> Could not find missing.jar\n'
log.write_text((timeout if case == 'stale' else '') + '* What went wrong:\n' + failure + '* Try:\n')
sys.exit(17)
''')
    recorder.chmod(0o700)
    curl = temporary / 'curl'
    curl.write_text('#!' + sys.executable + '\n' + r'''import json, os, sys
args = sys.argv[1:]
assert args[0] == '--disable'
assert '--head' in args and '--fail' in args and '--ipv4' in args
assert args[args.index('--proto') + 1] == '=https'
assert args[args.index('--noproxy') + 1] == 'maven.eazytec-cloud.com'
assert args[args.index('--connect-timeout') + 1] == '5'
assert args[args.index('--max-time') + 1] == '15'
assert not any(x in args for x in ['-k', '--insecure', '-L', '--location'])
assert args[-1] == 'https://maven.eazytec-cloud.com/nexus/repository/maven-public/org/example/metadata.jar'
with open(os.environ['PROBE_CALLS'], 'a') as output: output.write(json.dumps(args) + '\n')
print('http=200 remote_ip=61.177.127.227 connect_seconds=0.1')
sys.exit(22 if os.environ['RESOLVE_CASE'] == 'once' else 0)
''')
    curl.chmod(0o700)
    (temporary / 'sleep').write_text('#!/usr/bin/env bash\nexit 0\n')
    (temporary / 'sleep').chmod(0o700)
    for case, count, expected_exit, probes in [('success', 1, 0, 0), ('once', 2, 0, 1),
                                             ('twice', 3, 0, 2), ('exhausted', 3, 17, 3),
                                             ('tls', 1, 17, 0), ('http', 1, 17, 0),
                                             ('compiler', 1, 17, 0), ('mixed', 1, 17, 0),
                                             ('stale', 1, 17, 0), ('wrong_host', 1, 17, 0),
                                             ('mixed_missing', 1, 17, 0)]:
        work = temporary / case; work.mkdir()
        calls, probe_calls = work / 'calls.jsonl', work / 'probe.jsonl'
        result = subprocess.run(['bash', '-euo', 'pipefail', '-c', resolve_commands], cwd=work,
                                env={**os.environ, 'PATH': directory + os.pathsep + os.environ['PATH'],
                                     'GITHUB_SHA': 'd' * 40, 'RESOLVE_CALLS': str(calls),
                                     'GITHUB_WORKSPACE': str(ROOT),
                                     'PROBE_CALLS': str(probe_calls), 'RESOLVE_CASE': case},
                                capture_output=True, text=True)
        assert result.returncode == expected_exit, (case, result.stdout, result.stderr)
        invocations = [json.loads(line) for line in calls.read_text().splitlines()]
        assert len(invocations) == count, (case, invocations)
        logs = []
        for invocation in invocations:
            args = invocation['args']
            assert invocation['version'] == 'source-r8-' + 'd' * 40
            assert ':diagnostics-core:transformCommonMainDependenciesMetadata' in args
            assert '-Dorg.gradle.internal.http.connectionTimeout=10000' in args
            assert '-Dorg.gradle.internal.http.socketTimeout=60000' in args
            assert '--no-daemon' in args and '--max-workers=1' in args
            assert [arg for arg in args if arg.startswith(':')] == [':diagnostics-core:transformCommonMainDependenciesMetadata']
            assert [arg for arg in args if 'max.tentatives' in arg] == ['-Dorg.gradle.internal.repository.max.tentatives=1'], 'Only metadata outer recovery owns retries'
            logs.append(args[args.index('--log') + 1])
        assert len(set(logs)) == count, 'Preserve each attempt separately'
        assert (len(probe_calls.read_text().splitlines()) if probe_calls.exists() else 0) == probes
print('Metadata recovery: 11 cases; bounded exact-host TCP retries, no compilation/test retry')

# 从 main 验旧版本仍执行原消费者；新四 Pod 验证只适用于 rc.19 及后续。
tagged_step = jobs['release-native'].split('      - name: Verify exact tagged iOS native consumers\n', 1)[1].split('      - name:', 1)[0]
tagged_commands = tagged_step.split('        run: |\n', 1)[1]
tagged_commands = '\n'.join(line[10:] for line in tagged_commands.splitlines())
with tempfile.TemporaryDirectory() as directory:
    temporary = Path(directory)
    recorder = temporary / 'python3'
    recorder.write_text('#!' + sys.executable + '\n' + r'''import json, os, sys
with open(os.environ['TAGGED_CALLS'], 'a') as output:
    output.write(json.dumps(sys.argv[1:]) + '\n')
with open(os.environ['TAGGED_CWDS'], 'a') as output:
    output.write(os.getcwd() + '\n')
''')
    recorder.chmod(0o700)
    git = temporary / 'git'
    git.write_text('#!' + sys.executable + '\n' + r'''import os, sys
from pathlib import Path
args = sys.argv[1:]
assert args == ['clone', '--depth', '1', '--branch', os.environ['VERSION'], 'https://github.com/gycrosskit/diagnostics.git', os.environ['RUNNER_TEMP'] + '/diagnostics-tagged-ios-metadata']
Path(args[-1]).mkdir(parents=True)
''')
    git.chmod(0o700)
    legacy = ['0.1.0', *[f'0.2.0-rc.{version}' for version in range(1, 19)]]
    for version in [*legacy, '0.2.0-rc.19', '0.2.0-rc.20', '0.3.0', '1.0.0']:
        calls = temporary / (version + '.jsonl')
        result = subprocess.run(['bash', '-euo', 'pipefail', '-c', tagged_commands], cwd=ROOT,
                                env={**os.environ, 'PATH': directory + os.pathsep + os.environ['PATH'],
                                     'VERSION': version, 'TAGGED_CALLS': str(calls), 'TAGGED_CWDS': str(calls) + '.cwd',
                                     'GITHUB_WORKSPACE': str(ROOT), 'RUNNER_TEMP': str(temporary / version)},
                                capture_output=True, text=True)
        assert result.returncode == 0, (version, result.stdout, result.stderr)
        invocations = [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
        if version in legacy:
            assert not invocations and 'skipped for ' + version in result.stdout, version
        else:
            assert len(invocations) == 1 and invocations[0][-4:] == ['env', 'TAGGED_IOS_DIAGNOSTICS=' + str(ROOT / 'ci-diagnostics/tagged-ios'), 'RUBYOPT=-r' + str(ROOT / 'scripts/verify-tagged-ios-sdk.rb'), 'pod'], version
            assert [Path(path).resolve() for path in Path(str(calls) + '.cwd').read_text().splitlines()] == [(temporary / version / 'diagnostics-tagged-ios-metadata').resolve()]
print('Tagged iOS release gate: legacy skips; installed pod launcher preloads exact verifier')
