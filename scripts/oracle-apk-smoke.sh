#!/usr/bin/env bash
set -euo pipefail
base='https://apk-test.anglesya.win'
evidence_dir="$RUNNER_TEMP/apk-test-evidence"
mkdir -m 700 -p "$evidence_dir"
apk=$(find app/build/outputs/apk/release -maxdepth 1 -type f -name 'Han1meViewer-v*.apk' -print -quit)
if [[ -z "$apk" ]]; then
  echo '::error::No arm64 Han1meViewer APK was produced.'
  echo 'result=missing_apk' >> "$GITHUB_OUTPUT"
  exit 0
fi
sha=$(sha256sum "$apk" | awk '{print $1}')
response="$evidence_dir/submit.json"
curl --silent --show-error --fail-with-body --connect-timeout 15 --max-time 900 \
  -H "CF-Access-Client-Id: $CF_ACCESS_CLIENT_ID" \
  -H "CF-Access-Client-Secret: $CF_ACCESS_CLIENT_SECRET" \
  -H "Authorization: Bearer $APK_TEST_API_TOKEN" \
  -H 'Content-Type: application/vnd.android.package-archive' \
  -H "X-APK-SHA256: $sha" \
  --data-binary "@$apk" "$base/v1/jobs" > "$response"
job_id=$(jq -er '.id | select(test("^[0-9a-f]{32}$"))' "$response")
echo "Submitted APK SHA-256 $sha as job $job_id"
printf 'job_id=%s\n' "$job_id" >> "$GITHUB_OUTPUT"
status_path="/v1/jobs/$job_id"
terminal=false
deadline=$((SECONDS + 1800))
while (( SECONDS < deadline )); do
  request_timeout=$((deadline - SECONDS))
  if (( request_timeout > 60 )); then request_timeout=60; fi
  curl --silent --show-error --fail-with-body --connect-timeout 15 --max-time "$request_timeout" \
    -H "CF-Access-Client-Id: $CF_ACCESS_CLIENT_ID" \
    -H "CF-Access-Client-Secret: $CF_ACCESS_CLIENT_SECRET" \
    -H "Authorization: Bearer $APK_TEST_API_TOKEN" \
    "$base$status_path" > "$evidence_dir/status.json.tmp"
  mv "$evidence_dir/status.json.tmp" "$evidence_dir/status.json"
  state=$(jq -r '.state // "unknown"' "$evidence_dir/status.json")
  if [[ "$state" == 'completed' || "$state" == 'failed' ]]; then
    terminal=true
    break
  fi
  remaining=$((deadline - SECONDS))
  if (( remaining <= 0 )); then break; fi
  if (( remaining < 15 )); then sleep "$remaining"; else sleep 15; fi
done
if [[ "$terminal" == true ]]; then
  curl --silent --show-error --fail-with-body --connect-timeout 15 --max-time 300 \
    -H "CF-Access-Client-Id: $CF_ACCESS_CLIENT_ID" \
    -H "CF-Access-Client-Secret: $CF_ACCESS_CLIENT_SECRET" \
    -H "Authorization: Bearer $APK_TEST_API_TOKEN" \
    "$base/v1/jobs/$job_id/archive.zip" -o "$evidence_dir/test-evidence.zip"
  test_result=$(jq -r '.result.status // .state // "unknown"' "$evidence_dir/status.json")
else
  test_result='timeout'
  echo '::error::Oracle smoke test did not finish within 30 minutes.'
fi
printf 'result=%s\n' "$test_result" >> "$GITHUB_OUTPUT"
{
  echo "### Oracle APK smoke test"
  echo "- Job: $job_id"
  echo "- Result: **$test_result**"
  echo "- SHA-256: $sha"
} >> "$GITHUB_STEP_SUMMARY"
