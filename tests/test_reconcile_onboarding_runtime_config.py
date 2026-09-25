import importlib.util, json, os, stat
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/"scripts"/"reconcile-onboarding-runtime-config.py"

def load():
    spec=importlib.util.spec_from_file_location("reconcile_onboarding",SCRIPT)
    mod=importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(mod)
    return mod

def test_candidate_changes_only_runtime_tenant():
    m=load()
    cfg={"ouf":{"authorization":{"ths":{"enabled":True}}},"server":{"port":8080},"spring":{"x":"secret"}}
    out=m.candidate(cfg,"ouf-lab")
    assert out["ouf"]["runtime-publications"]["tenant-id"]=="ouf-lab"
    assert out["ouf"]["authorization"]==cfg["ouf"]["authorization"]
    assert out["server"]==cfg["server"]
    assert out["spring"]==cfg["spring"]
    assert "runtime-publications" not in cfg["ouf"]

def test_projection_requires_onboarding_tenant():
    m=load()
    assert m.projected_tenant({"onboarding":{"environment":{"OUF_RUNTIME_PUBLICATIONS_TENANT_ID":"tenant-a"}}})=="tenant-a"
    try:
        m.projected_tenant({})
        assert False
    except ValueError as exc:
        assert str(exc)=="PROJECTION_ONBOARDING_TENANT_MISSING"

def test_atomic_write_preserves_mode(tmp_path):
    m=load()
    p=tmp_path/"config.json"
    p.write_text('{"ouf":{}}\n')
    os.chmod(p,0o400)
    st=p.stat()
    m.atomic_write(p,{"ouf":{"runtime-publications":{"tenant-id":"t"}}},st)
    assert stat.S_IMODE(p.stat().st_mode)==0o400
    assert json.loads(p.read_text())["ouf"]["runtime-publications"]["tenant-id"]=="t"
