#!/usr/bin/env bash
set -uo pipefail

api="https://api.telegram.org/bot${TG_BOT_TOKEN}"
short_sha="${HEAD_SHA:0:12}"
apk="$(find app/build/outputs -type f -name '*.apk' -print -quit 2>/dev/null || true)"

send_message() {
  curl -fsS --max-time 30 -X POST "$api/sendMessage" \
    -d chat_id="$TG_CHAT_ID" \
    --data-urlencode text="$1" >/dev/null || true
}

if [[ "$BUILD_STATUS" == "success" && -n "$apk" && -s "$apk" ]]; then
  size="$(stat -c '%s' "$apk" 2>/dev/null || printf '0')"
  name="$(basename "$apk")"
  send_message "ECH 构建成功
提交：$short_sha
运行：$RUN_ID
APK：$name
大小：$size 字节
正在发送本次运行的 APK。"
  curl -fsS --max-time 120 -X POST "$api/sendDocument" \
    -F chat_id="$TG_CHAT_ID" \
    -F document="@$apk" \
    -F caption="Han1meViewer ECH APK\n提交：$short_sha\n运行：$RUN_ID" >/dev/null || \
    send_message "本次构建成功，但 APK 附件发送失败。提交：$short_sha，运行：$RUN_ID"
else
  log="$(gh run view "$RUN_ID" --repo "$GITHUB_REPOSITORY" --log-failed 2>/dev/null || true)"
  error="$(printf '%s\n' "$log" | grep -E 'e: file://|FAILURE:|Execution failed|Process completed with exit code' | tail -20 || true)"
  [[ -n "$error" ]] || error="未能从 Actions 日志提取编译行，请查看运行详情。"
  send_message "ECH 构建失败
提交：$short_sha
运行：$RUN_ID
状态：$BUILD_STATUS
真实错误摘要：
$error"
fi