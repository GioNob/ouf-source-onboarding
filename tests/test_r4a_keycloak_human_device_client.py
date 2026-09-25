import importlib.util
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("device_client", SCRIPTS / "r4a_keycloak_human_device_client.py")
device = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(device)


def test_exact_identity_and_public_oidc_contract(monkeypatch):
    client = {"id": "uuid", "clientId": "ouf-human-admin", "protocol": "openid-connect",
              "publicClient": True, "enabled": True, "standardFlowEnabled": False}
    def get_json(container, *args):
        return [client] if args[0] == "get" and args[1] == "clients" else client
    monkeypatch.setattr(device, "get_json", get_json)
    assert device.exact_client("kc", "ouf", "ouf-human-admin") == client
    monkeypatch.setattr(device, "get_json", lambda container, *args: [client] if args[1] == "clients" else {**client, "publicClient": False})
    with pytest.raises(device.ScopeError, match="CLIENT_CONTRACT_MISMATCH"):
        device.exact_client("kc", "ouf", "ouf-human-admin")


def test_client_id_must_remain_exact(monkeypatch):
    monkeypatch.setattr(device, "get_json", lambda *args: [{"id": "uuid", "clientId": "ouf-human-admin-other"}])
    with pytest.raises(device.ScopeError, match="CLIENT_MISSING"):
        device.exact_client("kc", "ouf", "ouf-human-admin")


def test_apply_preserves_other_client_settings(monkeypatch, capsys):
    original = {"id": "uuid", "clientId": "ouf-human-admin", "publicClient": True,
                "enabled": True, "protocol": "openid-connect", "standardFlowEnabled": False,
                "directAccessGrantsEnabled": False, "attributes": {"existing": "value"}}
    updated = []
    monkeypatch.setattr(device, "exact_client", lambda *args: original if not updated else updated[0])
    def run(container, *args, input_text):
        import json
        updated.append(json.loads(input_text))
    monkeypatch.setattr(device, "run", run)
    monkeypatch.setattr(sys, "argv", ["script", "apply"])
    device.main()
    assert updated == [{**original, "oauth2DeviceAuthorizationGrantEnabled": True}]
    assert "VERIFY=PASS" in capsys.readouterr().out
