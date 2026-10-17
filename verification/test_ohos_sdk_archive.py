"""归档检查器的合成 fixture；不把此测试当作 Hvigor 编译证据。"""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('verify_ohos_sdk', ROOT / 'scripts/verify-ohos-sdk.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class HarInventoryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.archive = Path(self.directory.name) / 'fixture.har'
        self.source = ROOT / 'ohos-sdk/core'

    def archive_fixture(self, change=None, runtime=False):
        package = json.loads((self.source / 'oh-package.json5').read_text())
        package['compatibleSdkVersion'] = 22
        if change:
            change(package)
        files = {'oh-package.json5': json.dumps(package).encode()}
        for file in [self.source / 'Index.ets', *self.source.glob('src/main/ets/*.ets')]:
            files[file.relative_to(self.source).as_posix()] = file.read_bytes()
        if runtime:
            files['libs/arm64-v8a/libkotlin.so'] = b'fixture'
        with tarfile.open(self.archive, 'w:gz') as har:
            for name, data in files.items():
                info = tarfile.TarInfo('package/' + name)
                info.size = len(data)
                har.addfile(info, io.BytesIO(data))
        return self.archive

    def test_exact_source_and_metadata_are_accepted(self):
        self.assertEqual(len(verifier.verify(self.archive_fixture(), self.source)), 64)

    def test_missing_hvigor_metadata_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'API22'):
            verifier.verify(self.archive_fixture(lambda package: package.pop('compatibleSdkVersion')), self.source)

    def test_dependency_drift_and_embedded_runtime_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'dependencies'):
            verifier.verify(self.archive_fixture(lambda package: package.update(dependencies={})), self.source)
        with self.assertRaisesRegex(ValueError, 'Kotlin runtime'):
            verifier.verify(self.archive_fixture(runtime=True), self.source)


if __name__ == '__main__':
    unittest.main()
