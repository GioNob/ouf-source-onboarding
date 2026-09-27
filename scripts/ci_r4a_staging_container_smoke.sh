#!/usr/bin/env bash
# Boot the actual release image with staging enabled, fake credentials and CI PostgreSQL.
# No production secret, MinIO bucket, Gateway route or external upload is used.
set -euo pipefail

image="${1:?pass the image tag built in CI}"
test "${OUF_ONB_DB_URL:-}" = 'jdbc:postgresql://127.0.0.1:5432/ouf_onboarding'
test -n "${OUF_ONB_DB_USER:-}" && test -n "${OUF_ONB_DB_PASSWORD:-}"
test "$(docker image inspect "$image" --format '{{.Config.User}}')" = '10003:10003'

work="$(mktemp -d)"
name="ouf-onboarding-staging-ci-${GITHUB_RUN_ID:-local}-$$"
cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# These values exist only inside this CI job. The files emulate the actual
# read-only mounts and verify that UID 10003 can read through /run/secrets.
chmod 755 "$work"
printf 'ci-only-access\n' > "$work/access"
printf 'ci-only-secret-not-for-any-server\n' > "$work/secret"
printf 'ci-only-not-a-jwt\n' > "$work/token"
chmod 444 "$work/access" "$work/secret" "$work/token"

docker run --detach --name "$name" --network host --user 10003:10003 \
  --env OUF_ONB_DB_URL --env OUF_ONB_DB_USER --env OUF_ONB_DB_PASSWORD \
  --env OUF_ONBOARDING_STAGING_ENDPOINT=http://127.0.0.1:9000 \
  --env OUF_ONBOARDING_STAGING_BUCKET=ouf-managed-files \
  --env OUF_ONBOARDING_STAGING_ACCESS_KEY_FILE=/run/secrets/onboarding-minio-access-key \
  --env OUF_ONBOARDING_STAGING_SECRET_KEY_FILE=/run/secrets/onboarding-minio-secret-key \
  --env OUF_ONBOARDING_OBJECT_STORE_GATEWAY_BASE_URL=http://127.0.0.1:9080 \
  --env OUF_ONBOARDING_OBJECT_STORE_TOKEN_FILE=/run/ouf-onboarding-auth/token \
  --mount "type=bind,source=$work/access,target=/run/secrets/onboarding-minio-access-key,readonly" \
  --mount "type=bind,source=$work/secret,target=/run/secrets/onboarding-minio-secret-key,readonly" \
  --mount "type=bind,source=$work,target=/run/ouf-onboarding-auth,readonly" \
  "$image" >/dev/null

for attempt in {1..45}; do
  if curl --silent --show-error --fail --max-time 3 \
       http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; then
    test "$(docker inspect "$name" --format '{{.State.Running}}')" = true
    echo 'STAGING_IMAGE_BOOT=PASS'
    echo 'READINESS_HTTP=200'
    echo 'FAKE_CREDENTIALS_ONLY=true'
    exit 0
  fi
  if test "$(docker inspect "$name" --format '{{.State.Running}}')" != true; then
    break
  fi
  sleep 2
done

echo 'STAGING_IMAGE_BOOT=FAIL' >&2
echo 'CI_CONTAINER_LOG_TAIL_BEGIN' >&2
docker logs --tail 80 "$name" >&2 || true
echo 'CI_CONTAINER_LOG_TAIL_END' >&2
exit 1
