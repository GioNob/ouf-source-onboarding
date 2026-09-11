#!/usr/bin/env sh
set -eu

test "$(sed -n 's/^baseline=//p' contracts/RC3_BASELINE_PIN.txt)" = "ouf-contracts-v1.0.0-rc3-coherence"
test "$(sed -n 's/^freezeManifestSha256=//p' contracts/RC3_BASELINE_PIN.txt)" = "1bb71e3c689dd21ccc3c3fcd8bcf238039b6aef79c3e9373032acf731476eea0"
sha256sum -c contracts/onboarding/SHA256SUMS.txt

