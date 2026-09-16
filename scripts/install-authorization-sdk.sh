#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/authorization-sdk"
sha256sum -c SOURCE_SHA256SUMS
exec timeout --signal=TERM --kill-after=30s 10m mvn -B -ntp clean install
