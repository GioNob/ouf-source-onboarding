import importlib.util
import json
from pathlib import Path

import pytest

ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/"scripts/r4a_installation_workload_update.py"


def load_subject():
    spec=importlib.util.spec_from_file_location("r4a_installation_workload_update",SCRIPT)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixture():
    return {
        "installationId":"ouf-lab-netcup-01",
        "iam":{"workloadClients":{"mcpServer":"ouf-mcp-server","ingestion":"ouf-ingestion"}},
        "lifecycle":{"revision":1,"status":"VALIDATED","checksum":"legacy"},
    }


def test_derive_adds_only_declared_workload_and_preserves_legacy_lifecycle():
    subject=load_subject()
    source=fixture()
    candidate=subject.derive(source,"udp","ouf-udp")
    subject.verify_only_change(source,candidate,"udp","ouf-udp")
    assert candidate["iam"]["workloadClients"]["udp"]=="ouf-udp"
    assert candidate["lifecycle"]==source["lifecycle"]
    assert source["iam"]["workloadClients"]=={
        "mcpServer":"ouf-mcp-server",
        "ingestion":"ouf-ingestion",
    }


def test_existing_conflicting_workload_fails_closed():
    subject=load_subject()
    source=fixture()
    source["iam"]["workloadClients"]["udp"]="other"
    with pytest.raises(subject.LifecycleError,match="WORKLOAD_KEY_CONFLICT"):
        subject.derive(source,"udp","ouf-udp")


def test_extra_candidate_change_is_rejected():
    subject=load_subject()
    source=fixture()
    candidate=subject.derive(source,"udp","ouf-udp")
    candidate["installationId"]="other"
    with pytest.raises(subject.LifecycleError,match="CANDIDATE_CONTAINS_EXTRA_CHANGES"):
        subject.verify_only_change(source,candidate,"udp","ouf-udp")


def test_script_never_logs_token_and_requires_all_installation_scopes():
    subject=load_subject()
    text=SCRIPT.read_text()
    assert "ACCESS_TOKEN_PRINTED=false" in text
    assert "authorization.policy.admin" not in subject.REQUIRED_SCOPES
    assert subject.REQUIRED_SCOPES=={
        "installation.configuration.read",
        "installation.configuration.write",
        "installation.configuration.activate",
        "installation.configuration.export",
    }
