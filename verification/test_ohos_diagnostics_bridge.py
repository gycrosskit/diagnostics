#!/usr/bin/env python3
"""编译真实 OHOS 诊断 Kotlin 桥；Renderer/Module transport 替身强制线程与销毁契约。"""
import argparse
from functools import partial
import os
from pathlib import Path
import subprocess
import tempfile

from kotlin_artifact import artifact

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resolve-dependencies', action='store_true')
    args = parser.parse_args()
    resolve = partial(artifact, resolve_dependencies=args.resolve_dependencies)
    libraries = [resolve('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
                 resolve('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'),
                 resolve('org.jetbrains', 'annotations', '13.0')]
    compiler = [resolve('org.jetbrains.kotlin', name, version) for name, version in [
        ('kotlin-compiler-embeddable', '2.2.21'), ('kotlin-script-runtime', '2.2.21'),
        ('kotlin-reflect', '1.6.10'), ('kotlin-daemon-embeddable', '2.2.21')]]
    classpath = os.pathsep.join(map(str, libraries))
    sources = list((Path(__file__).parent / 'fixtures/diagnostics-bridge').glob('*.kt'))
    sources.append(ROOT / 'diagnostics-kuikly/src/ohosArm64Main/kotlin/io/github/gycrosskit/diagnostics/kuikly/OhosDiagnosticSdkBridge.kt')
    with tempfile.TemporaryDirectory(prefix='ohos-diagnostics-bridge-') as directory:
        output = Path(directory) / 'regression.jar'
        subprocess.run(['java', '-cp', os.pathsep.join(map(str, compiler)) + os.pathsep + classpath,
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-classpath', classpath, '-d', str(output), *map(str, sources)], check=True)
        subprocess.run(['java', '-cp', str(output) + os.pathsep + classpath, 'DiagnosticsBridgeMainKt'], check=True)


if __name__ == '__main__':
    main()
