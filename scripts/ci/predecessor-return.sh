#!/usr/bin/env bash
set -euo pipefail

ref=${PREDECESSOR:-82aabaa46eb015e79441b249b4308787866f34c0}
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
case "${PREDECESSOR_MODE:-source}" in
  governed)
    test "$previous" = "${PREDECESSOR_COMMIT:?}"
    previous_image=${LOADED_PREDECESSOR_IMAGE:?}
    test "$previous_image" = "${PREDECESSOR_IMAGE_ID:?}"
    provenance="immagine governata di Onboarding #81, run $PREDECESSOR_RUN, artefatto $PREDECESSOR_ARTIFACT"
    ;;
  source)
    build_revision "$previous"
    previous_image=$(cat "$work/$previous.iid")
    provenance="sorgente, non governato"
    ;;
  *) echo 'Unknown predecessor mode' >&2; exit 1 ;;
esac
build_revision "$candidate"
candidate_image=$(cat "$work/$candidate.iid")
summary="ONB-58P-01 / D-080 — predecessore \`$previous\` ($provenance), immagine \`$previous_image\`. Candidato \`$candidate\`, immagine \`$candidate_image\`."
echo "$summary"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  echo "$summary" >> "$GITHUB_STEP_SUMMARY"
fi
node scripts/ci/predecessor-return.mjs "$previous_image" "$candidate_image"
