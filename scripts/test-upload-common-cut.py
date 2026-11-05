#!/usr/bin/env python3
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

class RestoreSettingsTest(unittest.TestCase):
    def test_native_wal_placeholders_are_preserved_literally(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,{'RUNNER_TEMP':directory}):
            spec=importlib.util.spec_from_file_location('cut',Path(__file__).with_name('ci-upload-common-cut.py'))
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
            settings=module.recovery_settings()
            self.assertIn("restore_command = 'cp /archive/%f %p'",settings)
            self.assertIn("recovery_target_name = 'ouf_onboarding_upload_frozen_v39'",settings)
            self.assertIn("recovery_target_action = 'promote'",settings)
            self.assertIn('hot_standby = off',settings)
            self.assertIn('archive_mode = off',settings)

if __name__=='__main__':unittest.main()
