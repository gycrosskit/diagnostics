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
r8_step = jobs['android'].split('      - name: Verify Android R8 from source staging publications\n', 1)[1].split('      - name:', 1)[0]
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
