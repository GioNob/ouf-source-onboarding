import importlib.util
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("binding", SCRIPTS / "r4a_keycloak_client_scope_binding.py")
binding = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(binding)


def test_exact_client_rejects_partial_match_and_duplicates(monkeypatch):
    monkeypatch.setattr(binding, "get_json", lambda *args: [{"clientId": "ouf-ingestion-other", "id": "x"}])
    with pytest.raises(binding.ScopeError, match="CLIENT_MISSING"):
        binding.exact_client("kc", "ouf", "ouf-ingestion")
    monkeypatch.setattr(binding, "get_json", lambda *args: [{"clientId": "ouf-ingestion", "id": "x"}] * 2)
    with pytest.raises(binding.ScopeError, match="CLIENT_NOT_UNIQUE"):
        binding.exact_client("kc", "ouf", "ouf-ingestion")


def test_binding_is_additive_and_wrong_kind_blocks():
    assert binding.state({"default": set(), "optional": set()}, "s", "default") == "MISSING"
    assert binding.state({"default": {"s", "other"}, "optional": set()}, "s", "default") == "BOUND"
    with pytest.raises(binding.ScopeError, match="CONFLICTING_BINDING:OPTIONAL"):
        binding.state({"default": set(), "optional": {"s"}}, "s", "default")


def test_reads_both_binding_kinds(monkeypatch):
    def get_json(container, *args):
        return [{"id": "s"}] if "default-client-scopes" in args[1] else [{"id": "other"}]
    monkeypatch.setattr(binding, "get_json", get_json)
    assert binding.bindings("kc", "ouf", "client") == {"default": {"s"}, "optional": {"other"}}
