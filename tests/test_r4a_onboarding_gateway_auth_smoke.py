import importlib.util
from pathlib import Path
import urllib.error

import pytest

SPEC = importlib.util.spec_from_file_location("smoke", Path(__file__).parents[1] / "scripts" / "r4a_human_token_scope_smoke.py")
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


def test_owner_not_found_proves_authorization_without_creating_source(monkeypatch, capsys):
    class Response:
        code = 404
        def read(self, *args): return b'{"code":"ONB_NOT_FOUND"}'
    def urlopen(request, timeout):
        assert request.method == "POST"
        assert request.data == b'{"configuration":{}}'
        assert "/sources/r4a-nonexistent-" in request.full_url
        raise urllib.error.HTTPError(request.full_url, 404, "Not Found", {}, Response())
    monkeypatch.setattr(smoke.urllib.request, "urlopen", urlopen)
    smoke.check_onboarding("token")
    assert "ONBOARDING_AUTHORIZED_NO_WRITE=true" in capsys.readouterr().out


def test_gateway_not_found_is_not_accepted(monkeypatch):
    class Response:
        code = 404
        def read(self, *args): return b'{"error_msg":"route not found"}'
    def urlopen(request, timeout):
        raise urllib.error.HTTPError(request.full_url, 404, "Not Found", {}, Response())
    monkeypatch.setattr(smoke.urllib.request, "urlopen", urlopen)
    with pytest.raises(smoke.SmokeError, match="ONBOARDING_GATEWAY_ACCEPTANCE_FAILED"):
        smoke.check_onboarding("token")
