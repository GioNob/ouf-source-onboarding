import importlib.util
from pathlib import Path
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
