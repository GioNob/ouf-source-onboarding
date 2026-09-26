import sys
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

sys.path.insert(0, str(Path(__file__).parents[1] / 'scripts'))
import r4a_admin_all_human_manifest as admin


class AdminManifestTest(unittest.TestCase):
    def test_human_only_existing_and_new(self):
        subject = 'b93d8cf6-cd14-4ee6-91d7-84cd76c4f500'
        policy = {
            'capabilities': [
                {'capabilityId': 'ouf.preview', 'allowedActors': ['HUMAN'], 'requiredScope': 'ouf.preview'},
                {'capabilityId': 'ouf.upload', 'allowedActors': ['HUMAN'], 'requiredScope': 'ouf.upload'},
                {'capabilityId': 'ouf.read-internal', 'allowedActors': ['SERVICE'], 'requiredScope': 'ouf.internal'},
            ],
            'grants': [{'grantId': 'existing', 'capabilityId': 'ouf.preview',
                        'tenantId': 'ouf-lab', 'subjectId': subject,
                        'validFrom': '2026-09-01T00:00:00Z',
                        'validUntil': '2099-01-01T00:00:00Z'}],
        }
        rows, scopes, covered = admin.build(policy, subject)
        self.assertEqual([r['capabilityId'] for r in rows], ['ouf.upload'])
        self.assertEqual(scopes, ['ouf.preview', 'ouf.upload'])
        self.assertEqual(covered, ['ouf.preview'])
        self.assertEqual(admin.build(policy, subject)[0], rows)

    def test_existing_manifest_not_overwritten(self):
        with TemporaryDirectory() as root:
            target = Path(root) / 'admin-grants.json'
            target.write_text('keep')
            with self.assertRaisesRegex(ValueError, 'MANIFEST_ALREADY_EXISTS'):
                admin.write_manifest(target, [{'grantId': 'g', 'capabilityId': 'c'}])
            self.assertEqual(target.read_text(), 'keep')


if __name__ == '__main__':
    unittest.main()
