import importlib.util
from pathlib import Path

SPEC=importlib.util.spec_from_file_location("m",Path(__file__).parents[1]/"scripts"/"r4a_keycloak_workload_client.py")
m=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(m)

def test_canonical_profile_matches_udp_contract():
    assert m.EXPECTED_CLIENT=={
        "enabled":True,
        "standardFlowEnabled":False,
        "directAccessGrantsEnabled":False,
        "serviceAccountsEnabled":True,
        "publicClient":False,
        "protocol":"openid-connect",
    }
    assert m.CANONICAL_SCOPES=={
        "acr","authorization.bundle.read","basic","email","profile","roles","service_account","web-origins"
    }
    assert m.MAPPERS["ouf-api-gateway-audience"]["config"]["included.client.audience"]=="ouf-api-gateway"
    assert m.MAPPERS["ouf-service-actor-type"]["config"]["claim.value"]=="SERVICE"
    assert m.MAPPERS["ouf-lab-tenant"]["config"]["claim.value"]=="ouf-lab"

def test_normalized_mapper_preserves_string_contract():
    row={"protocol":"openid-connect","protocolMapper":"x","config":{"a":True,"b":"false"}}
    assert m.normalized_mapper(row)=={"protocol":"openid-connect","protocolMapper":"x","config":{"a":"true","b":"false"}}
