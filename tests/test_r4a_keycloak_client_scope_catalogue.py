import importlib.util
from pathlib import Path

SPEC=importlib.util.spec_from_file_location("m",Path(__file__).parents[1]/"scripts"/"r4a_keycloak_client_scope_catalogue.py")
m=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(m)

def test_desired_scope_is_oidc_and_emitted_in_scope_claim():
    assert m.desired("ouf.example")=={
        "name":"ouf.example",
        "protocol":"openid-connect",
        "attributes":{
            "include.in.token.scope":"true",
            "display.on.consent.screen":"false",
        },
    }

def test_drift_detects_missing_and_semantic_mismatch():
    assert m.drift(None)==["SCOPE_MISSING"]
    assert m.drift({"protocol":"saml","attributes":{}})==[
        "PROTOCOL",
        "ATTRIBUTE:include.in.token.scope",
        "ATTRIBUTE:display.on.consent.screen",
    ]
    assert m.drift(m.desired("ouf.example"))==[]

def test_extra_attributes_do_not_create_false_drift():
    scope=m.desired("ouf.example")
    scope["attributes"]["gui.order"]="7"
    assert m.drift(scope)==[]
