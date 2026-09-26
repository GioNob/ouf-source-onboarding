import contextlib
import io
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / 'scripts'))
import r4a_minio_staging_bootstrap as stage


class MinioStagingBootstrapTest(unittest.TestCase):
    def test_policy_is_confined_to_dedicated_object_prefix(self):
        self.assertEqual(stage.POLICY['Statement'][0]['Resource'],
                         ['arn:aws:s3:::ouf-managed-files/managed-files/*'])
        self.assertNotIn('s3:DeleteObject', stage.POLICY['Statement'][0]['Action'])

    def test_apply_uses_private_secret_without_printing(self):
        with TemporaryDirectory() as root:
            directory = Path(root)
            access, secret = directory / 'access', directory / 'secret'
            commands = []
            def write(path, value):
                (access if path == stage.ACCESS else secret).write_text(value + '\n')
            with patch.object(stage, 'create_file', side_effect=write), \
                    patch.object(stage, 'SECRET', secret), \
                    patch.object(stage, 'ACCESS', access), \
                    patch.object(stage, 'run_shell', side_effect=lambda command: commands.append(command) or ''), \
                    patch.object(stage.secrets, 'token_hex', return_value='ab' * 32):
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    stage.install(False, {'BUCKET': False, 'POLICY': False, 'USER': False})
            self.assertIn('POLICY_ATTACHED=true', output.getvalue())
            self.assertNotIn('ab' * 32, output.getvalue())
            self.assertEqual(secret.read_text().strip(), 'ab' * 32)
            self.assertTrue(all(subprocess.run(['sh', '-n'], input=script, text=True,
                                               capture_output=True).returncode == 0 for script in commands))


if __name__ == '__main__':
    unittest.main()
