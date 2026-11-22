#!/usr/bin/env bash
set -euo pipefail

ref=${PREDECESSOR:-b3f33586}
[[ "$ref" =~ ^[0-9a-fA-F]{7,40}$ ]] || { echo 'Expected a predecessor commit SHA' >&2; exit 1; }
previous=$(git rev-parse --verify "${ref}^{commit}")
candidate=$(git rev-parse --verify 'HEAD^{commit}')
test "$previous" != "$candidate"
test "$candidate" = "${GITHUB_SHA:-$candidate}"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
build_revision() {
  local sha=$1 dir="$work/$1"
  mkdir "$dir"
  git archive "$sha" | tar -x -C "$dir"
  docker build --iidfile "$dir.iid" --label "org.opencontainers.image.revision=$sha" "$dir"
  local image
  image=$(cat "$dir.iid")
  test "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")" = "$sha"
  echo "Verified revision $sha, image $image"
}
build_revision "$previous"
build_revision "$candidate"
node scripts/ci/predecessor-return.mjs "$(cat "$work/$previous.iid")" "$(cat "$work/$candidate.iid")"
