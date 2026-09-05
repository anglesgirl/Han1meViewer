#!/usr/bin/env bash
set -euo pipefail
REPO="${GITHUB_REPOSITORY:-anglesgirl/Han1meViewer}"
RUN_ID="${GITHUB_RUN_ID:-unknown}"
SHA="${GITHUB_SHA:-unknown}"
STATUS_RAW="${BUILD_STATUS:-}"
STATUS=$(echo "$STATUS_RAW" | tr -d '\r' | xargs | tr '[:upper:]' '[:lower:]')
SHORT_SHA="${SHA:0:7}"
RUN_URL="https://github.com/${REPO}/actions/runs/${RUN_ID}"
BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-}"
CHAT_ID="${TELEGRAM_CHAT_ID:-}"
if [ -z "$BOT_TOKEN" ] || [ -z "$CHAT_ID" ]; then echo "missing TELEGRAM secrets, skip notify"; exit 0; fi
send_msg(){ curl -sS -X POST "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" -d chat_id="${CHAT_ID}" -d parse_mode="Markdown" --data-urlencode "text=$1" | head -c 800; echo; }
send_doc(){ curl -sS -X POST "https://api.telegram.org/bot${BOT_TOKEN}/sendDocument" -F chat_id="${CHAT_ID}" -F caption="$1" -F document=@"$2" | head -c 800; echo; }
if [ "$STATUS" = "success" ]; then
  send_msg "✅ 主应用构建成功 \`$SHORT_SHA\` | run \`$RUN_ID\`
[查看运行](${RUN_URL})"
  APK=$(find app/build/outputs/apk -name "Han1meViewer-v*.apk" 2>/dev/null | head -n1 || true)
  if [ -n "$APK" ] && [ -f "$APK" ]; then
    echo "sending APK $APK"
    send_doc "Han1meViewer $SHORT_SHA run $RUN_ID" "$APK"
  else
    echo "no Han1meViewer APK found"
  fi
else
  send_msg "❌ 主应用构建失败 \`$SHORT_SHA\` | run \`$RUN_ID\` | \`$STATUS\`
[查看运行](${RUN_URL})"
fi
