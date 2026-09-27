import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("smoke", Path(__file__).parents[1] / "scripts" / "r4a_human_token_scope_smoke.py")
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


def test_exact_scope_and_identity_acceptance():
    claims = {"iss": smoke.ISSUER, "azp": smoke.CLIENT, "preferred_username": "ouf-admin",
              "ouf_actor_type": "HUMAN", "aud": ["ouf-api-gateway"],
              "scope": "openid ouf.onboarding.configuration.write.extra ouf.onboarding.configuration.write",
              "iat": 100, "exp": 9999999999}
    assert all(smoke.acceptance(claims, 100).values())
    claims["scope"] = "openid ouf.onboarding.configuration.write.extra"
    assert smoke.acceptance(claims, 100)["SCOPE_PRESENT"] is False
    claims["scope"] = smoke.SCOPE
    claims["preferred_username"] = "another-user"
    assert smoke.acceptance(claims, 100)["USER_MATCH"] is False
