#!/usr/bin/env bash
#
# 把本地生成的资源包塞进调试版（不上传仓库）。
#
# 用法：
#   tools/publish-pack.sh --local 202611141300
#   tools/push-pack-local.sh 202611141300
#
# 为什么只能推调试版：Preview / Release 是 debuggable=false，adb 拿不到 filesDir；
# 调试版可以 `run-as`。包名可用 NBU_PKG 覆盖。
#
# 做完这一步，应用下次启动就会读 filesDir/pack/<版本>/ 下的文件，
# 调试面板「资源包」会显示「当前：远端 v<版本>」，并列出每个文件的来源。
set -euo pipefail

# Git Bash 会把 /data/local/tmp/... 当成本地路径改写掉（变成 C:/Program Files/...），
# adb 于是报 remote secure_mkdirs() failed。这行必须放在任何 adb 调用之前。
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/.."

VERSION="${1:-}"
if ! [[ "$VERSION" =~ ^[0-9]{12}$ ]]; then
  echo "用法：tools/push-pack-local.sh <12 位版本号>" >&2
  exit 2
fi

PKG="${NBU_PKG:-app.nbult.lite.debug}"
SRC="dist/pack/$VERSION"
if [ ! -d "$SRC/$VERSION" ]; then
  echo "找不到 $SRC/$VERSION，先跑：tools/publish-pack.sh --local $VERSION" >&2
  exit 1
fi

ADB="${ADB:-adb}"
TMP="/data/local/tmp/nbu-pack-$VERSION"

echo "==> 推送文件到设备"
"$ADB" shell rm -rf "$TMP"
"$ADB" push "$SRC/$VERSION" "$TMP" >/dev/null
"$ADB" shell chmod -R 755 "$TMP"

NOW=$(( $(date +%s) * 1000 ))
STATE=$(cat <<EOF
{"version":$VERSION,"source":"remote","appliedAt":$NOW,"lastCheckAt":$NOW,"notes":"本地测试包 $VERSION","lastAttemptAt":$NOW,"failedVersion":0,"failedAt":0,"failedReason":""}
EOF
)

echo "==> 写入应用私有目录（$PKG）"
# 远程命令必须整体加单引号：adb shell 会把多个参数用空格拼起来，双引号会被拆散
"$ADB" shell "run-as $PKG sh -c 'rm -rf files/pack/$VERSION; mkdir -p files/pack/$VERSION'"
"$ADB" shell "run-as $PKG sh -c 'cp -r $TMP/. files/pack/$VERSION/'"
printf '%s' "$STATE" | "$ADB" shell "run-as $PKG sh -c 'cat > files/pack/state.json'"

echo "==> 确认写入结果"
"$ADB" shell "run-as $PKG sh -c 'find files/pack/$VERSION -type f; echo ---; cat files/pack/state.json'"

echo "==> 重启应用"
"$ADB" shell am force-stop "$PKG"
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

echo
echo "完成。接下来看："
echo "  1) 「调试 → 资源包」应显示 当前：远端 v$VERSION，下面每个文件显示 pack@$VERSION"
echo "  2) 入口页出现 8 条湘潭入口（含「包内测试条目」）"
echo "  3) 日志 grep [Pack] 与 [Term]"
