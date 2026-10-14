from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def test_access_snapshot_helper_is_device_flow_and_token_safe():
    raw=(ROOT/"scripts/r4a_access_snapshot.py").read_text()
    assert "ouf-human-admin" in raw
    assert "authorization.permissions.read" in raw
    assert "ACCESS_TOKEN_PRINTED=false" in raw
    assert "ACCESS_FILE_MODE=0600" in raw
    assert "nextAfter" in raw
    assert "os.replace" in raw
