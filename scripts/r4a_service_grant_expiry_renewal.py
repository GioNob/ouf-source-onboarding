#!/usr/bin/env python3
"""Renew expired nominal SERVICE grants through the HUMAN Authorization owner API."""
from __future__ import annotations

import argparse
from pathlib import Path
import sys

import r4a_human_grant_renewal as renewal
from r4a_human_grant_lifecycle import SUBJECT_RE, human_token


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "draft", "preview", "publish", "verify"))
    parser.add_argument("--subject-id", required=True, help="HUMAN administrator subject for Device Flow")
    parser.add_argument("--grants-file", type=Path, required=True,
                        help="JSON list of grantId, capabilityId and servicePrincipalId selectors")
    parser.add_argument("--valid-until", required=True)
    parser.add_argument("--state-file", type=Path, required=True)
    parser.add_argument("--confirm-publish", action="store_true")
    parser.add_argument("--reviewed-draft-hash", help="Complete sha256: hash from the approved preview")
    parser.set_defaults(valid_from=None)
    args = parser.parse_args()
    renewal.need(SUBJECT_RE.fullmatch(args.subject_id), "INVALID_SUBJECT_ID")
    renewal.run(args, human_token, grant_type="SERVICE")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, TypeError) as exc:
        code = str(exc) if isinstance(exc, (ValueError, RuntimeError)) else type(exc).__name__
        print("SERVICE_GRANT_RENEWAL_BLOCKED=" + code, file=sys.stderr)
        raise SystemExit(1)
