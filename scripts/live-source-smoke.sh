#!/usr/bin/env bash
set -euo pipefail
mkdir -p evidence
url='https://api.worldbank.org/v2/country/ITA/indicator/SP.POP.TOTL?format=json&per_page=3'
body="$(curl --fail --silent --show-error --location --connect-timeout 10 --max-time 30 --retry 2 --retry-delay 2 "$url")"
printf '%s' "$body" | jq -e 'type=="array" and length==2 and .[1]|type=="array" and length>0' >/dev/null
count="$(printf '%s' "$body" | jq '.[1]|length')"
latest="$(printf '%s' "$body" | jq -r '.[1][0] | [.countryiso3code,.date,(.value|tostring),.indicator.id] | @tsv')"
jq -n --arg sourceId world-bank --arg indicator SP.POP.TOTL --argjson observations "$count" --arg latest "$latest" --arg checkedAt "$(date -u +%FT%TZ)" '{status:"PASS",sourceId:$sourceId,indicator:$indicator,observations:$observations,latestObservation:$latest,checkedAt:$checkedAt}' > evidence/live-source-smoke.json
cat evidence/live-source-smoke.json
