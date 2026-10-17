#!/usr/bin/env python3
"""检查真实 R8 APK 的 SPI 描述和实现保留；不执行或初始化 SDK。"""
import argparse
from pathlib import Path
import re
import tempfile
import zipfile

SERVICE = 'META-INF/services/io.github.gycrosskit.diagnostics.sdk.AndroidDiagnosticSdkFactory'


def verify(apk, mapping, provider):
    if not re.fullmatch(r'[a-z][a-z0-9-]{0,63}', provider):
        raise ValueError('Invalid provider identifier')
    with zipfile.ZipFile(apk) as archive:
        descriptor = archive.read(SERVICE).decode() if SERVICE in archive.namelist() else ''
        factories = [line.split('#', 1)[0].strip() for line in descriptor.splitlines()]
        factories = [line for line in factories if line]
        expected = 0 if provider == 'none' else 1
        if len(factories) != expected:
            raise ValueError(f'Expected {expected} installed SPI factories, got {factories}')
        dex = [archive.read(name) for name in archive.namelist() if re.fullmatch(r'classes[0-9]*\.dex', name)]
        if not dex:
            raise ValueError('Minified APK has no DEX')
        names = Path(mapping).read_text()
        for factory in factories:
            if not factory.startswith('io.github.gycrosskit.diagnostics.' + provider + '.'):
                raise ValueError('SPI factory does not match selected adapter')
            if factory + ' -> ' + factory + ':' not in names:
                raise ValueError('SPI implementation was renamed or removed by R8')
            encoded = ('L' + factory.replace('.', '/') + ';').encode()
            if not any(encoded in data for data in dex):
                raise ValueError('SPI implementation is missing from minified DEX')
    return factories


def self_test():
    # 合成字节只测试检查器会拒绝 SPI 丢失/改名；不是 SDK 或 R8 执行证据。
    factory = 'io.github.gycrosskit.diagnostics.bugly.BuglyAndroidDiagnosticSdkFactory'
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        apk, mapping = root / 'fixture.apk', root / 'mapping.txt'
        mapping.write_text(factory + ' -> ' + factory + ':\n')
        def fixture(service='', dex=b'fixture-dex'):
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', dex)
                if service: archive.writestr(SERVICE, service)
        fixture()
        assert verify(apk, mapping, 'none') == []
        encoded = ('L' + factory.replace('.', '/') + ';').encode()
        fixture(factory + '\n', encoded)
        assert verify(apk, mapping, 'bugly') == [factory]
        for descriptor, dex, mapped in [('', encoded, True), (factory + '\n', b'removed', True),
                                        (factory + '\n', encoded, False)]:
            fixture(descriptor, dex)
            mapping.write_text(factory + (' -> ' + factory if mapped else ' -> a.b') + ':\n')
            try:
                verify(apk, mapping, 'bugly')
            except ValueError:
                pass
            else:
                raise AssertionError('Broken SPI fixture must fail')
    print('R8 SPI checker: none/registered/stripped/removed/renamed passed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--mapping', type=Path)
    parser.add_argument('--provider')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if not args.apk or not args.mapping or not args.provider:
            parser.error('--apk, --mapping and --provider are required')
        print('R8 APK SPI verified:', args.provider, verify(args.apk, args.mapping, args.provider))
