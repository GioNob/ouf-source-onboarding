import importlib.util
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/"scripts"/"r4a_service_grant_lifecycle.py"

def load():
    spec=importlib.util.spec_from_file_location("service_grants",SCRIPT)
    mod=importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(mod)
    return mod

def test_plan_adds_missing_service_grants_only():
    m=load()
    active={"policy":{"bundleId":"b","version":18,"publishedAt":"x","capabilities":[
        {"capabilityId":"ouf.semantic.read","operation":"READ","requiredScope":"ouf.semantic.read","allowedActors":["SERVICE"]}
    ],"grants":[
        {"grantId":"existing","capabilityId":"ouf.semantic.read","tenantId":"ouf-lab","subjectId":None,"servicePrincipalId":"other","organizationId":None,"validFrom":"2026-01-01T00:00:00Z","validUntil":"2030-01-01T00:00:00Z","constraints":None}
    ]}}
    wanted=[{"grantId":"grant-semantic-read-udp","capabilityId":"ouf.semantic.read","tenantId":"ouf-lab","subjectId":None,"servicePrincipalId":"ouf-udp","organizationId":None,"validFrom":"2026-09-18T07:12:50.968730Z","validUntil":"2036-09-15T07:13:50.968730Z","constraints":None}]
    policy,missing=m.plan(active,wanted)
    assert policy["grants"][0]["grantId"]=="existing"
    assert missing==wanted

def test_plan_rejects_missing_active_capability():
    m=load()
    active={"policy":{"bundleId":"b","version":18,"publishedAt":"x","capabilities":[],"grants":[]}}
    wanted=[{"grantId":"g","capabilityId":"ouf.semantic.read","tenantId":"ouf-lab","subjectId":None,"servicePrincipalId":"ouf-udp","organizationId":None,"validFrom":"2026-01-01T00:00:00Z","validUntil":"2030-01-01T00:00:00Z","constraints":None}]
    try:
        m.plan(active,wanted)
        assert False
    except ValueError as exc:
        assert str(exc)=="CAPABILITY_NOT_ACTIVE=ouf.semantic.read"
