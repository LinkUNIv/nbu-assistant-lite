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
  resp=$(curl -sS --max-time 30 "$url" 2>/dev/null || true)
  if [ -z "$resp" ]; then
    echo "    无响应（网络不通？），稍后重试"
    continue
  fi
  # 只关心两个 provider 是否都清了：CF / FY。
  # 这里用纯 shell 判断，不内联 python —— 之前的版本因为引号嵌套直接语法错误。
  flat=$(printf '%s' "$resp" | tr -d ' \n\r\t')
  case "$flat" in
    *'"CF":true'*'"FY":true'*) echo "    ok" ;;
    *) echo "    部分失败：$resp" ;;
  esac
done

echo
echo "注意：purge 只清 jsDelivr，raw 是实时的，不需要处理。"
echo "若显示部分失败，等 1 分钟再跑一次（jsDelivr 有节流）。"
