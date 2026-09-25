#!/usr/bin/env python3
"""Interactive Device Flow smoke; never prints or persists the access token."""
from __future__ import annotations

import base64
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ISSUER = "https://auth.ouf-lab.it/realms/ouf"
CLIENT = "ouf-human-admin"
SCOPE = "ouf.onboarding.configuration.write"


class SmokeError(RuntimeError):
    pass


def post(url, fields):
    body = urllib.parse.urlencode(fields).encode("ascii")
    request = urllib.request.Request(url, data=body,
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        try:
            error = json.load(exc).get("error", "unknown")
        except (ValueError, OSError):
            error = "unknown"
        return {"error": error}


def claims_from_token(token):
    parts = token.split(".")
    if len(parts) != 3:
        raise SmokeError("ACCESS_TOKEN_FORMAT")
    encoded = parts[1]
    try:
        return json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
    except (ValueError, UnicodeDecodeError) as exc:
        raise SmokeError("ACCESS_TOKEN_CLAIMS") from exc


def acceptance(claims, requested_at):
    aud = claims.get("aud", [])
    if isinstance(aud, str):
        aud = [aud]
    return {
        "ISSUER_MATCH": claims.get("iss") == ISSUER,
        "CLIENT_MATCH": claims.get("azp") == CLIENT,
        "USER_MATCH": claims.get("preferred_username") == "ouf-admin",
        "ACTOR_IS_HUMAN": claims.get("ouf_actor_type") in ("HUMAN", "HUMAN_USER"),
        "GATEWAY_AUDIENCE": "ouf-api-gateway" in aud,
        "SCOPE_PRESENT": SCOPE in str(claims.get("scope", "")).split(),
        "FRESH": claims.get("iat", 0) >= requested_at - 5,
        "NOT_EXPIRED": claims.get("exp", 0) > time.time(),
    }


def main():
    with urllib.request.urlopen(ISSUER + "/.well-known/openid-configuration", timeout=15) as response:
        metadata = json.load(response)
    if metadata.get("issuer") != ISSUER:
        raise SmokeError("ISSUER_DISCOVERY_MISMATCH")
    device_endpoint = metadata.get("device_authorization_endpoint")
    token_endpoint = metadata.get("token_endpoint")
    if not device_endpoint or not token_endpoint:
        raise SmokeError("DEVICE_ENDPOINT_MISSING")
    requested_at = int(time.time())
    device = post(device_endpoint, {"client_id": CLIENT, "scope": "openid " + SCOPE})
    if "error" in device:
        raise SmokeError("DEVICE_REQUEST:" + str(device["error"]))
    if not all(device.get(key) for key in ("device_code", "user_code", "verification_uri")):
        raise SmokeError("DEVICE_RESPONSE_INCOMPLETE")
    print("Apri nel browser del PC:", device["verification_uri"], flush=True)
    print("Inserisci il codice mostrato sul server. Non incollarlo in chat.", flush=True)
    print("CODICE:", device["user_code"], flush=True)
    interval = max(5, int(device.get("interval", 5)))
    deadline = time.monotonic() + min(600, int(device.get("expires_in", 600)))
    while time.monotonic() < deadline:
        time.sleep(interval)
        result = post(token_endpoint, {"grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                                       "device_code": device["device_code"], "client_id": CLIENT})
        error = result.get("error")
        if error == "authorization_pending":
            continue
        if error == "slow_down":
            interval += 5
            continue
        if error:
            raise SmokeError("TOKEN_REQUEST:" + str(error))
        token = result.get("access_token")
        if not isinstance(token, str):
            raise SmokeError("ACCESS_TOKEN_MISSING")
        outcome = acceptance(claims_from_token(token), requested_at)
        for key, value in outcome.items():
            print(key + "=" + str(value).lower())
        if not all(outcome.values()):
            raise SmokeError("TOKEN_ACCEPTANCE_FAILED")
        print("TOKEN_ACCEPTANCE=PASS")
        return
    raise SmokeError("DEVICE_CODE_EXPIRED")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, TypeError, KeyError, SmokeError) as exc:
        code = str(exc) if isinstance(exc, SmokeError) else type(exc).__name__
        print("HUMAN_TOKEN_SMOKE_BLOCKED=" + code, file=sys.stderr)
        raise SystemExit(1)
