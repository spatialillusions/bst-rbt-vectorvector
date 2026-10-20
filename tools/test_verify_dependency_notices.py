import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_dependency_notices import MANIFEST, runtime_coordinates, verify


class DependencyNoticesTest(unittest.TestCase):
    def fixture(self, text=b'original notice', resource=True):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        jar = Path(folder.name)/'example.jar'
        record = {'coordinate': 'example:lib:1.0', 'license': 'MIT', 'files': [{
            'path': 'META-INF/example/LICENSE', 'source': 'fixture upstream',
            'sha256': hashlib.sha256(b'original notice').hexdigest()}]}
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr(MANIFEST, json.dumps({'dependencies': [record]}))
            if resource:
                archive.writestr('META-INF/example/LICENSE', text)
            archive.writestr('META-INF/licenses/vectorvector/LICENSE', b'project license')
            archive.writestr('META-INF/licenses/vectorvector/THIRD-PARTY-NOTICES.md', b'index')
        return jar

    def test_packaged_inventory_matches_resolution(self):
        expected = runtime_coordinates('   example:lib:jar:1.0:compile -- module example\n')
        self.assertEqual(verify(self.fixture(), expected, b'project license')['runtime_artifacts'], 1)

    def test_unreviewed_dependency_is_rejected(self):
        with self.assertRaises(ValueError):
            verify(self.fixture(), {'example:lib:1.0', 'new:lib:2.0'}, b'project license')

    def test_changed_notice_is_rejected(self):
        with self.assertRaises(ValueError):
            verify(self.fixture(text=b'changed'), {'example:lib:1.0'}, b'project license')

    def test_missing_notice_is_rejected(self):
        with self.assertRaises(KeyError):
            verify(self.fixture(resource=False), {'example:lib:1.0'}, b'project license')

    def test_changed_project_license_is_rejected(self):
        with self.assertRaises(ValueError):
            verify(self.fixture(), {'example:lib:1.0'}, b'changed project license')

    def test_empty_resolution_is_rejected(self):
        with self.assertRaises(ValueError):
            runtime_coordinates('No dependencies here')
