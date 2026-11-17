#!/usr/bin/env bash
# 刷新 jsDelivr 对清单文件的缓存。
#
# 为什么需要：应用与资源包都优先读 raw.githubusercontent.com，raw 不通时回落到
# cdn.jsdelivr.net。而 jsDelivr 对**分支内容**是长缓存（实测十几小时），
# 新版本发布后它还在发旧清单，用户点「检查更新」就永远看到「已是最新」。
# 0.38.18 及更早版本没有交叉核对，只能靠这一步把缓存轰掉。
#
# 用法：发布完成后跑一次（CI 写完清单之后）
#   bash tools/purge-cdn.sh
set -u

BRANCH="${PACK_BRANCH:-packs}"
REPO="LinkUNIv/nbu-assistant-lite"

for file in release/latest-preview.json release/latest-debug.json; do
  url="https://purge.jsdelivr.net/gh/$REPO@$BRANCH/$file"
  echo "purge $file"
  curl -sS --max-time 30 "$url" | python -c "
import sys, json
try:
    d = json.load(sys.stdin)
    ok = all(v.get('providers', {}).get(p) for p in ('CF', 'FY')
             for v in d.get('paths', {}).values())
    print('   ', 'ok' if ok else '部分失败', d.get('status', ''))
except Exception as e:
    print('  解析失败:', e)
"
done

echo
echo "注意：purge 只清 jsDelivr，raw 是实时的，不需要处理。"
echo "若 provider 显示 false，等 1 分钟再跑一次（jsDelivr 有节流）。"
