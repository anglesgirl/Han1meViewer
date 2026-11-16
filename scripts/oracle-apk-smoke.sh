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
apk_size=$(stat -c '%s' "$apk")
max_apk_size=$((250 * 1024 * 1024))
if (( apk_size <= 0 || apk_size > max_apk_size )); then
  echo "::error::APK size ${apk_size} bytes is outside the 1-byte to 250-MiB upload-session limit."
  echo 'result=invalid_apk_size' >> "$GITHUB_OUTPUT"
  exit 1
fi
sha=$(sha256sum "$apk" | awk '{print $1}')
response="$evidence_dir/submit.json"
session_file="$evidence_dir/upload-session.json"
api_error() {
  local file=$1
  if jq -e . "$file" >/dev/null 2>&1; then
    local message
    message=$(jq -r '.error // .message // "request failed"' "$file")
    echo "::error::Oracle upload API: $message"
  else
    echo '::error::Oracle upload API returned an unreadable error response.'
  fi
}
auth_headers=(
  -H "CF-Access-Client-Id: $CF_ACCESS_CLIENT_ID"
  -H "CF-Access-Client-Secret: $CF_ACCESS_CLIENT_SECRET"
  -H "Authorization: Bearer $APK_TEST_API_TOKEN"
)
retry_args=(--retry 4 --retry-connrefused --retry-delay 5)
upload_id=''
job_id=''
state='uploading'
if [[ -n "${APK_UPLOAD_ID:-}" ]]; then
  if [[ ! "$APK_UPLOAD_ID" =~ ^[0-9a-f]{32}$ ]]; then
    echo '::error::APK_UPLOAD_ID must be a 32-character lowercase hexadecimal upload-session ID.'
    echo 'result=invalid_upload_id' >> "$GITHUB_OUTPUT"
    exit 1
  fi
  upload_id=$APK_UPLOAD_ID
  if ! curl --silent --show-error --fail-with-body --connect-timeout 15 --max-time 30 "${retry_args[@]}" \
    "${auth_headers[@]}" "$base/v1/uploads/$upload_id" > "$session_file"; then
    api_error "$session_file"
    echo '::error::Unable to resume this session. It may have expired after 24 hours.'
    echo 'result=upload_resume_failed' >> "$GITHUB_OUTPUT"
    exit 1
  fi
  if ! jq -e --arg sha "$sha" --argjson size "$apk_size" '.sha256 == $sha and .size == $size' "$session_file" >/dev/null; then
    echo '::error::The existing upload session belongs to a different APK size or SHA-256; refusing to mix files.'
    echo 'result=upload_resume_mismatch' >> "$GITHUB_OUTPUT"
    exit 1
  fi
  state=$(jq -er '.state' "$session_file")
  echo "Resuming upload session $upload_id (state: $state)."
else
  init_json=$(jq -cn --argjson size "$apk_size" --arg sha "$sha" '{size:$size,sha256:$sha}')
  if ! curl --silent --show-error --fail-with-body --connect-timeout 15 --max-time 30 "${retry_args[@]}" \
    "${auth_headers[@]}" -H 'Content-Type: application/json' --data-binary "$init_json" \
    "$base/v1/uploads" > "$response"; then
    api_error "$response"
    echo 'result=upload_session_failed' >> "$GITHUB_OUTPUT"
    exit 1
  fi
  upload_id=$(jq -er '.id | select(test("^[0-9a-f]{32}$"))' "$response")
  cp "$response" "$session_file"
  state='uploading'
  echo "Created resumable upload session $upload_id for APK SHA-256 $sha ($apk_size bytes)."
fi
printf 'upload_id=%s\n' "$upload_id" >> "$GITHUB_OUTPUT"
printf 'upload_id=%s\n' "$upload_id" >> "$GITHUB_STEP_SUMMARY"
if [[ "$state" == 'completed' ]]; then
  job_id=$(jq -er '.job_id // .id | select(test("^[0-9a-f]{32}$"))' "$session_file")
elif [[ "$state" == 'uploading' || "$state" == 'finalizing' ]]; then
  if [[ "$state" == 'uploading' ]]; then
    chunk_size=$(jq -er '.chunk_size | numbers | select(. > 0)' "$session_file")
    chunk_count=$(jq -er '.chunk_count | numbers | select(. > 0)' "$session_file")
    max_request=$((90 * 1024 * 1024))
    if (( chunk_size > max_request )); then
      echo "::error::Server chunk size ${chunk_size} bytes exceeds the 90-MiB single-request limit."
      echo 'result=invalid_server_chunk_size' >> "$GITHUB_OUTPUT"
      exit 1
    fi
    chunk_dir=$(mktemp -d "$RUNNER_TEMP/apk-upload-chunks.XXXXXX")
    trap 'rm -rf "$chunk_dir"' EXIT
    for ((index = 0; index < chunk_count; index++)); do
      present=$(jq -r --argjson index "$index" '((.received // []) | index($index)) != null' "$session_file")
      if [[ "$present" == 'true' ]]; then
        echo "Chunk $((index + 1))/$chunk_count already verified; skipping."
        continue
      fi
      offset=$((index * chunk_size))
      chunk_length=$((apk_size - offset))
      if (( chunk_length > chunk_size )); then chunk_length=$chunk_size; fi
      chunk_file="$chunk_dir/chunk-$index.bin"
      dd if="$apk" of="$chunk_file" bs=4M iflag=skip_bytes,count_bytes skip="$offset" count="$chunk_length" status=none
      actual_chunk_size=$(stat -c '%s' "$chunk_file")
      if (( actual_chunk_size != chunk_length )); then
        echo "::error::Local chunk $index was incomplete (${actual_chunk_size}/${chunk_length} bytes)."
        echo 'result=local_chunk_failed' >> "$GITHUB_OUTPUT"
        exit 1
      fi
      chunk_sha=$(sha256sum "$chunk_file" | awk '{print $1}')
      chunk_response="$chunk_dir/chunk-response.json"
      if ! curl --silent --show-error --fail-with-body --connect-timeout 15 --max-time 900 "${retry_args[@]}" \
        "${auth_headers[@]}" -X PUT \
        -H 'Content-Type: application/octet-stream' \
        -H "X-Chunk-SHA256: $chunk_sha" \
        --data-binary "@$chunk_file" "$base/v1/uploads/$upload_id/chunks/$index" > "$chunk_response"; then
        api_error "$chunk_response"
        echo "::error::Chunk $((index + 1))/$chunk_count failed after retries; rerun with this upload ID and the same APK to resume."
        echo 'result=chunk_upload_failed' >> "$GITHUB_OUTPUT"
        exit 1
      fi
      if ! curl --silent --show-error --fail-with-body --connect-timeout 15 --max-time 30 "${retry_args[@]}" \
        "${auth_headers[@]}" "$base/v1/uploads/$upload_id" > "$session_file"; then
        api_error "$session_file"
        echo 'result=upload_status_failed' >> "$GITHUB_OUTPUT"
        exit 1
      fi
      present=$(jq -r --argjson index "$index" '((.received // []) | index($index)) != null' "$session_file")
      if [[ "$present" != 'true' ]]; then
        echo "::error::Server did not confirm chunk $index after upload."
        echo 'result=chunk_confirmation_failed' >> "$GITHUB_OUTPUT"
        exit 1
      fi
      echo "Uploaded and verified chunk $((index + 1))/$chunk_count (${chunk_length} bytes)."
    done
  fi
  if ! curl --silent --show-error --fail-with-body --connect-timeout 15 --max-time 900 "${retry_args[@]}" \
    "${auth_headers[@]}" -X POST -H 'Content-Type: application/json' -H 'Content-Length: 0' \
    "$base/v1/uploads/$upload_id/complete" > "$response"; then
    api_error "$response"
    echo '::error::Could not finalize the APK upload; rerun with this upload ID and the same APK to resume.'
    echo 'result=upload_finalize_failed' >> "$GITHUB_OUTPUT"
    exit 1
  fi
  job_id=$(jq -er '.id | select(test("^[0-9a-f]{32}$"))' "$response")
else
  echo "::error::Unsupported upload-session state: $state"
  echo 'result=invalid_upload_state' >> "$GITHUB_OUTPUT"
  exit 1
fi
printf 'job_id=%s\n' "$job_id" >> "$GITHUB_OUTPUT"
echo "Submitted APK SHA-256 $sha as job $job_id"
status_path="/v1/jobs/$job_id"
terminal=false
deadline=$((SECONDS + 1800))
while (( SECONDS < deadline )); do
  request_timeout=$((deadline - SECONDS))
  if (( request_timeout > 60 )); then request_timeout=60; fi
  if ! curl --silent --show-error --fail-with-body --connect-timeout 15 --max-time "$request_timeout" "${retry_args[@]}" \
    "${auth_headers[@]}" "$base$status_path" > "$evidence_dir/status.json.tmp"; then
    api_error "$evidence_dir/status.json.tmp"
    echo 'result=status_poll_failed' >> "$GITHUB_OUTPUT"
    exit 1
  fi
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
  if ! curl --silent --show-error --fail-with-body --connect-timeout 15 --max-time 300 "${retry_args[@]}" \
    "${auth_headers[@]}" "$base/v1/jobs/$job_id/archive.zip" -o "$evidence_dir/test-evidence.zip"; then
    api_error "$evidence_dir/test-evidence.zip"
    echo 'result=evidence_download_failed' >> "$GITHUB_OUTPUT"
    exit 1
  fi
  test_result=$(jq -r '.result.status // .state // "unknown"' "$evidence_dir/status.json")
else
  test_result='timeout'
  echo '::error::Oracle smoke test did not finish within 30 minutes.'
fi
printf 'result=%s\n' "$test_result" >> "$GITHUB_OUTPUT"
{
  echo "### Oracle APK smoke test"
  echo "- Upload session: $upload_id"
  echo "- Job: $job_id"
  echo "- Result: **$test_result**"
  echo "- APK size: $apk_size bytes, split into requests no larger than 64 MiB"
  echo "- SHA-256: $sha"
} >> "$GITHUB_STEP_SUMMARY"
