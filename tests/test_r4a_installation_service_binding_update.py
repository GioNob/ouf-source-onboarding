import importlib.util
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/"scripts"/"r4a_installation_service_binding_update.py"

def load():
    spec=importlib.util.spec_from_file_location("svc_bindings",SCRIPT)
    mod=importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(mod)
    return mod

def test_derive_adds_only_service_bindings():
    m=load()
    src={"networking":{"backendNetwork":"b","edgeNetwork":"e","gatewayControlNetwork":"g","internalDnsStrategy":"REVERSE_PROXY_ALIAS"},"other":{"x":1}}
    out=m.derive(src,{"ouf-semantic-registry":"ouf-semantic"})
    assert out["networking"]["serviceBindings"]=={"ouf-semantic-registry":"ouf-semantic"}
    assert out["networking"]["backendNetwork"]=="b"
    assert out["other"]=={"x":1}
    assert "serviceBindings" not in src["networking"]

def test_derive_refuses_conflicting_binding():
    m=load()
    src={"networking":{"serviceBindings":{"logical":"runtime-a"}}}
    try:
        m.derive(src,{"logical":"runtime-b"})
        assert False
    except m.LifecycleError as exc:
        assert str(exc)=="SERVICE_BINDING_CONFLICT=logical"

def test_parse_bindings_is_closed_and_deterministic():
    m=load()
    assert m.parse_bindings(["b=runtime-b","a=runtime-a"])=={"b":"runtime-b","a":"runtime-a"}
    for bad in ["missing-separator","bad/name=runtime","name=bad/name"]:
        try:
            m.parse_bindings([bad])
            assert False
        except m.LifecycleError:
            pass


def test_script_reads_payload_from_revision_endpoint():
    raw=SCRIPT.read_text()
    assert '/active' in raw
    assert '/revisions/{a.expected_source_revision}' in raw
    assert 'revision_view.get("payload")' in raw
    assert 'active.get("payload")' not in raw
