#!/usr/bin/env python3
"""获取远程 Renderer 契约测试的固定 Kotlin/JVM 工具依赖。"""
from pathlib import Path
import argparse
import hashlib
import os
import re
import subprocess
import tempfile
import zipfile
from urllib.request import urlopen
from functools import partial

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'

def artifact(group, name, version, suffix='.jar', resolve_dependencies=False):
    paths = sorted((CACHE / group / name / version).glob(f'*/{name}-{version}{suffix}'))
    if paths:
        return paths[0]
    filename = f'{name}-{version}{suffix}'
    target = ROOT / 'build/diagnostics-bridge-dependencies' / filename
    if target.is_file():
        return target
    if not resolve_dependencies:
        raise SystemExit(f'缺少已解析依赖 {group}:{name}:{version}:{suffix}；使用 --resolve-dependencies 补齐')
    repository = 'https://jitpack.io' if group == 'com.github.gycrosskit.wechat' else 'https://repo.maven.apache.org/maven2'
    url = f'{repository}/{group.replace(".", "/")}/{name}/{version}/{filename}'
    with urlopen(url, timeout=30) as response:
        data = response.read()
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return target

