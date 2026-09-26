import importlib.util
from pathlib import Path
import os
import stat

import pytest

SPEC=importlib.util.spec_from_file_location("secret",Path(__file__).parents[1]/"scripts/r4a_keycloak_workload_secret_file.py")
module=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(module)


def test_writes_private_new_file_and_never_overwrites(tmp_path):
    target=tmp_path/"secret"
    module.store(target,"dummy-ci-only")
    assert target.read_text()=="dummy-ci-only"
    assert stat.S_IMODE(target.stat().st_mode)==0o600
    with pytest.raises(FileExistsError):module.store(target,"replacement-ci-only")
    assert target.read_text()=="dummy-ci-only"


def test_rejects_blank_or_multiline_secret(tmp_path):
    for value in ("", "secret\nother"):
        with pytest.raises(module.SecretError,match="KEYCLOAK_SECRET_INVALID"):
            module.store(tmp_path/"secret",value)
    assert not (tmp_path/"secret").exists()


def test_dedicated_directory_plan_is_read_only_and_apply_is_private(tmp_path):
    base=tmp_path/"ouf"
    base.mkdir(mode=0o755)
    base.chmod(0o755)
    directory=base/"secrets"
    assert module.check_directory(directory,expected_uid=os.getuid()) is False
    assert not directory.exists()
    assert module.check_directory(directory,create=True,expected_uid=os.getuid()) is True
    assert stat.S_IMODE(directory.stat().st_mode)==0o700
    assert module.check_directory(directory,expected_uid=os.getuid()) is True


def test_rejects_writable_parent_or_symlinked_secret_directory(tmp_path):
    base=tmp_path/"ouf"
    base.mkdir()
    base.chmod(0o770)
    with pytest.raises(module.SecretError,match="SECRET_BASE_DIRECTORY_UNSAFE"):
        module.check_directory(base/"secrets",expected_uid=os.getuid())
    base.chmod(0o700)
    (base/"secrets").symlink_to(tmp_path,target_is_directory=True)
    with pytest.raises(module.SecretError,match="SECRET_DIRECTORY_UNSAFE"):
        module.check_directory(base/"secrets",expected_uid=os.getuid())
