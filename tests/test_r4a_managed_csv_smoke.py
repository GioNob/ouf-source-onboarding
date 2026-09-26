import importlib.util
from pathlib import Path
import time

import pytest

SPEC=importlib.util.spec_from_file_location("smoke",Path(__file__).parents[1]/"scripts/r4a_managed_csv_smoke.py")
smoke=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(smoke)


def test_exact_uploaded_fixture_is_accepted_without_modification():
    # The user-provided file is not committed to Git; this gate checks the pinned
    # size/hash before any token acquisition or write on the server.
    assert smoke.SHA=="a07c2dcdc21aa9a23fb5585a69d52031dc08010d251bf39bfa67c8e0962c6e1a"
    assert len(smoke.SHA)==64


def test_csv_changed_during_transfer_fails_before_device_login(tmp_path):
    path=tmp_path/"cinema.csv"
    path.write_bytes(b"cinema,indirizzo\nOther,Elsewhere\n")
    with pytest.raises(smoke.SmokeError,match="CSV_BYTES_OR_SHA_MISMATCH"):
        smoke.exact_file(path)


def test_profile_rejects_unredacted_address():
    preview={"format":"CSV","metadata":{"rows":8},
             "columns":[{"name":"cinema"},{"name":"indirizzo"}],
             "redactedSample":[{"cinema":"Cinema A","indirizzo":"Via something"}]}
    with pytest.raises(smoke.SmokeError,match="PROFILE_CONTRACT_MISMATCH"):
        smoke.check_preview(preview)


def test_named_human_identity_requires_exact_username_and_tenant():
    now=int(time.time())
    claims={"sub":"subject-1","preferred_username":"ouf-admin","tenant_id":"ouf-lab",
            "iss":"https://issuer.example","azp":"ouf-human-admin","ouf_actor_type":"HUMAN",
            "aud":["ouf-api-gateway"],"scope":" ".join(sorted(smoke.SCOPES)),
            "iat":now,"exp":now+300}
    def allowed(**changes):
        return smoke.accepted_claims({**claims,**changes},None,"ouf-admin","ouf-lab",
                                     "https://issuer.example","ouf-human-admin","ouf-api-gateway",now)
    assert allowed()
    for bad in ({"sub":""},{"preferred_username":"someone-else"},{"tenant_id":"other"},
                {"ouf_actor_type":"SERVICE"},{"aud":["wrong"]},{"scope":"openid"}):
        assert not allowed(**bad)
    assert smoke.accepted_claims(claims,"subject-1",None,"ouf-lab",
                                 "https://issuer.example","ouf-human-admin","ouf-api-gateway",now)
