#!/usr/bin/env python3
"""无需网络/Gradle的最小回归：摘要、身份、snapshot、exact inventory 与完整 Mock HTTP 检查。"""
import hashlib
import importlib.util
import io
import json
import tempfile
import urllib.error
import zipfile
from pathlib import Path

path = Path(__file__).resolve().parents[1] / 'scripts' / 'check-public-maven.py'
spec = importlib.util.spec_from_file_location('public_checker', path)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def rejects(action):
    try:
        action()
    except ValueError:
        return
    raise AssertionError('Expected checker to reject invalid evidence')


def main():
    version, commit = '0.1.0-rc.1', 'a' * 40
    group, repo, module = 'com.github.gycrosskit.example', 'example', 'example-core'
    state = {'status': 'ok', 'isTag': True, 'private': False, 'version': version, 'commit': commit, 'modules': [module]}
    checker.validate_state(state, version, commit, [module])
    for update in ({'isTag': False}, {'private': True}, {'commit': 'b' * 40}, {'modules': [module, module]}):
        rejects(lambda update=update: checker.validate_state(dict(state, **update), version, commit, [module]))
    snapshot = dict(state, version='main-SNAPSHOT')
    rejects(lambda: checker.validate_state(snapshot, 'main-SNAPSHOT', commit, [module]))
    checker.validate_component({'group': group, 'module': module, 'version': version}, group, module, repo, version)
    checker.validate_component({'group': 'com.github.gycrosskit', 'module': repo, 'version': version}, group, module, repo, version)
    # 合法 JitPack 变换必须成对；不能把旧来源 group 与任意 publication 混在一起。
    rejects(lambda: checker.validate_component({'group': 'com.github.gycrosskit', 'module': module, 'version': version}, group, module, repo, version))
    rejects(lambda: checker.validate_component({'group': group, 'module': repo, 'version': version}, group, module, repo, version))
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_STORED) as packed:
        packed.writestr('sample.txt', 'valid')
    data = archive.getvalue()
    item = {'name': module + '-' + version + '.jar', 'url': module + '-' + version + '.jar', 'size': len(data)}
    item.update({algorithm: hashlib.new(algorithm, data).hexdigest() for algorithm in checker.HASHES})
    checker.validate_file(item, data, 'https://example.test/artifact')
    rejects(lambda: checker.validate_file(dict(item, sha256='0' * 64), data, 'https://example.test/artifact'))
    damaged = bytearray(data)
    damaged[data.index(b'valid')] ^= 1
    corrupted_item = dict(item, **{algorithm: hashlib.new(algorithm, damaged).hexdigest() for algorithm in checker.HASHES})
    rejects(lambda: checker.validate_file(corrupted_item, bytes(damaged), 'https://example.test/artifact'))
    base = 'https://jitpack.io/' + group.replace('.', '/') + '/' + module + '/' + version + '/'
    prefix = base + module + '-' + version
    metadata = {'component': {'group': group, 'module': module, 'version': version},
                'variants': [{'name': 'metadataApiElements', 'files': [item]}]}
    pom = f'''<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{group}</groupId><artifactId>{module}</artifactId><version>{version}</version><licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url><distribution>repo</distribution></license></licenses></project>'''.encode()
    responses = {'https://jitpack.io/api/builds/com.github.gycrosskit/' + repo + '/' + version: json.dumps(state).encode(),
                 prefix + '.module': json.dumps(metadata).encode(), prefix + '.pom': pom, base + item['name']: data}
    for url, content in list(responses.items()):
        if '/api/builds/' not in url:
            for algorithm in ('md5', 'sha1'):
                responses[url + '.' + algorithm] = hashlib.new(algorithm, content).hexdigest().encode()

    def fetch(url):
        if url in responses:
            return responses[url]
        raise urllib.error.HTTPError(url, 404, 'Missing', {}, None)

    with tempfile.TemporaryDirectory() as directory:
        proof = checker.audit(repo, version, commit, [module], Path(directory) / 'good', fetch=fetch)
        assert proof['moduleCount'] == 1 and proof['uniqueFileCount'] == 1
        assert not proof['publicHigherSidecarsComplete']
        assert len(proof['missingPublicHigherSidecars']) == 3
        assert all(result['verified'] == ['md5', 'sha1'] for result in proof['modules'][0]['sidecars'])
        responses[base + item['name'] + '.sha1'] = b'0' * 40
        rejects(lambda: checker.audit(repo, version, commit, [module], Path(directory) / 'bad-digest', fetch=fetch))
        assert not (Path(directory) / 'bad-digest' / 'proof.json').exists()
    print('public Maven checker: state/identity/digest/ZIP CRC/mock HTTP/sidecar absence checks passed')


if __name__ == '__main__':
    main()
