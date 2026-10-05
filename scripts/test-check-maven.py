"""Run the actual checker against complete and deliberately damaged staging."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

GROUP = 'example.component'
VERSION = '1.0.0'


def checksums(path):
    for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
        path.with_name(path.name + '.' + algorithm).write_text(hashlib.new(algorithm, path.read_bytes()).hexdigest())


def stage(root):
    for name, native in (('demo', False), ('demo-iosarm64', True)):
        folder = root / GROUP.replace('.', '/') / name / VERSION
        folder.mkdir(parents=True)
        artifact = folder / (name + '-' + VERSION + ('.klib' if native else '.jar'))
        artifact.write_bytes(b'actual fixture artifact')
        entry = {'name': artifact.name, 'url': artifact.name, 'size': artifact.stat().st_size}
        entry.update({algorithm: hashlib.new(algorithm, artifact.read_bytes()).hexdigest() for algorithm in ('md5', 'sha1', 'sha256', 'sha512')})
        variants = [{'name': 'iosArm64ApiElements-published' if native else 'metadataApiElements', 'attributes': {'org.jetbrains.kotlin.native.target': 'ios_arm64'} if native else {}, 'files': [entry]}]
        if not native:
            variants.append({'name': 'iosArm64ApiElements-published', 'available-at': {'group': GROUP, 'module': 'demo-iosarm64', 'version': VERSION, 'url': '../../demo-iosarm64/1.0.0/demo-iosarm64-1.0.0.module'}})
        module = folder / (name + '-' + VERSION + '.module')
        module.write_text(json.dumps({'component': {'group': GROUP, 'module': name, 'version': VERSION}, 'variants': variants}))
        pom = module.with_suffix('.pom')
        pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>' + GROUP + '</groupId><artifactId>' + name + '</artifactId><version>' + VERSION + '</version><licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url><distribution>repo</distribution></license></licenses></project>')
        for path in (artifact, module, pom): checksums(path)
    return root / GROUP.replace('.', '/') / 'demo' / VERSION / 'demo-1.0.0.module'


class CheckerTest(unittest.TestCase):
    def test_complete_and_damaged_publications(self):
        checker = Path(__file__).resolve().parents[1] / 'verification/check-maven.py'
        for damage in ('none', 'missing_declared_hash', 'wrong_artifact', 'missing_module_sidecar', 'missing_artifact_sidecar', 'foreign_redirect', 'dangling_variant', 'unexpected_publication', 'missing_license'):
            with self.subTest(damage=damage), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                module = stage(root)
                metadata = json.loads(module.read_text())
                artifact = next(module.parent.glob('*.jar'))
                if damage == 'missing_declared_hash': del metadata['variants'][0]['files'][0]['sha512']
                if damage == 'wrong_artifact': artifact.write_bytes(b'corrupted'); checksums(artifact)
                if damage == 'missing_module_sidecar': module.with_name(module.name + '.sha512').unlink()
                if damage == 'missing_artifact_sidecar': artifact.with_name(artifact.name + '.sha1').unlink()
                if damage == 'foreign_redirect': metadata['variants'][1]['available-at']['group'] = 'foreign.group'
                if damage == 'dangling_variant': metadata['variants'][1]['name'] = 'missingApiElements-published'
                if damage == 'unexpected_publication':
                    clone = copy.deepcopy(metadata); clone['component']['module'] = 'extra'
                    folder = module.parent.parent.parent / 'extra' / VERSION; folder.mkdir(parents=True)
                    extra_artifact = folder / artifact.name; extra_artifact.write_bytes(artifact.read_bytes()); checksums(extra_artifact)
                    extra = folder / 'extra-1.0.0.module'; extra.write_text(json.dumps(clone)); checksums(extra)
                    pom = extra.with_suffix('.pom'); pom.write_text(module.with_suffix('.pom').read_text().replace('<artifactId>demo</artifactId>', '<artifactId>extra</artifactId>')); checksums(pom)
                if damage == 'missing_license':
                    pom = module.with_suffix('.pom'); text = pom.read_text(); a = text.index('<licenses>'); b = text.index('</licenses>') + len('</licenses>'); pom.write_text(text[:a] + text[b:]); checksums(pom)
                if damage not in ('missing_module_sidecar',): module.write_text(json.dumps(metadata)); checksums(module)
                result = subprocess.run([sys.executable, str(checker), str(root), GROUP, VERSION, 'demo', 'ios_arm64', 'demo,demo-iosarm64', '--jvm-only'], text=True, capture_output=True)
                self.assertEqual(result.returncode == 0, damage == 'none', result.stdout + result.stderr)
                if damage == 'unexpected_publication': self.assertIn('Missing or unexpected publications', result.stderr)


if __name__ == '__main__': unittest.main()
