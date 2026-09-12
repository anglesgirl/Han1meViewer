#!/usr/bin/env bash
# 校验 WebView 传输桥的 JS（不用 Android、不用编译）：从 Kotlin 常量里抽出 JS，
# 语法检查 + 跑接管逻辑的断言。放进 CI 是为了防"改 Kotlin 时把 JS 字符串改坏"这类静默事故。
set -euo pipefail

KT="app/src/main/java/com/yenaly/han1meviewer/logic/network/ech/EchWebBridgeJs.kt"
OUT="${TMPDIR:-/tmp}/ech_bridge.js"

python3 - "$KT" "$OUT" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'private val TEMPLATE = """(.*?)"""\.trimIndent\(\)', src, re.S)
if not m:
    sys.exit('未能从 Kotlin 常量里抽出 JS 模板（EchWebBridgeJs.kt 结构变了？）')
js = m.group(1).replace('__ECH_PROTECTED__', '["hanime1.me","javchu.com"]')
if '$' in js:
    sys.exit('JS 里出现了 $ —— 会和 Kotlin 字符串插值冲突，必须避免')
open(sys.argv[2], 'w', encoding='utf-8').write(js)
print('已抽出 JS: %d 行' % js.count('\n'))
PY

node --check "$OUT"
echo "JS 语法 OK ✓"
node .github/scripts/ech-bridge-js/test.js "$OUT"
