#!/usr/bin/env python3
"""校验真实 Hvigor 产物身份、源 API、依赖与纯 ArkTS 边界，再复制归档。"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile


def verify(archive, source):
    source = Path(source)
    expected = json.loads((source / 'oh-package.json5').read_text())
    with tarfile.open(archive, 'r:gz') as har:
        members = har.getmembers()
        names = [member.name for member in members]
        if len(names) != len(set(names)) or any(not (member.isfile() or member.isdir()) for member in members):
            raise ValueError('HAR contains ambiguous or linked members')
        if any(name.endswith(('.so', '.a', '.klib')) for name in names):
            raise ValueError('ArkTS adapter must not package another Kotlin runtime')
        def contents(name):
            member = har.getmember('package/' + name)
            if not member.isfile():
                raise ValueError('HAR member must be a file: ' + name)
            return har.extractfile(member).read()
        metadata = json.loads(contents('oh-package.json5'))
        for key in ('name', 'version', 'dependencies'):
            if metadata.get(key) != expected.get(key):
                raise ValueError('HAR metadata differs from source: ' + key)
        if metadata.get('compatibleSdkVersion') != 22:
            raise ValueError('HAR must declare actual Hvigor API22 compatibility metadata')
        for file in [source / 'Index.ets', *sorted((source / 'src/main/ets').glob('*.ets'))]:
            if contents(file.relative_to(source).as_posix()) != file.read_bytes():
                raise ValueError('HAR differs from source API: ' + file.name)
    return hashlib.sha256(Path(archive).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--copy-to', type=Path)
    args = parser.parse_args()
    archive = args.archive
    if archive is None:
        archives = list((args.source / 'build').glob('**/outputs/**/*.har'))
        if len(archives) != 1:
            parser.error('Expected one actual Hvigor HAR output, found ' + str(len(archives)))
        archive = archives[0]
    digest = verify(archive, args.source)
    if args.copy_to:
        args.copy_to.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(archive, args.copy_to)
    print(archive.name + ' SHA256=' + digest)


if __name__ == '__main__':
    main()
