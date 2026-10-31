#!/usr/bin/env bash
#
# 发布一个资源包到 packs 分支。
#
# 用法：
#   tools/publish-pack.sh            # 版本号取当前时间 yyyyMMddHHmm
#   tools/publish-pack.sh 202611011200
#
# 做什么：
#   1. 把 assets 里需要外置的文件复制到 packs/<版本>/ 下
#   2. 计算每个文件的 sha256，写进 packs/latest.json
#   3. 提交并推送到 packs 分支
#
# 为什么是单独的分支：main 上有 CI，push 一次就构建一次 APK；
# 资源包一天可能发几次，不该每次都触发 5 分钟的构建。
# 客户端固定从两个地址取（见 ResourcePackChecker），都指向这个分支。
#
# 注意：版本号只能升不能降，客户端会拒绝比本地更旧的包。
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="${1:-$(date +%Y%m%d%H%M)}"
if ! [[ "$VERSION" =~ ^[0-9]{12}$ ]]; then
  echo "版本号必须是 12 位 yyyyMMddHHmm，收到：$VERSION" >&2
  exit 1
fi

REMOTE=$(git remote get-url origin)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "==> 取 packs 分支"
if ! git clone --quiet --branch packs --depth 1 "$REMOTE" "$WORK/packs" 2>/dev/null; then
  echo "    packs 分支还不存在，初始化一个空的"
  mkdir -p "$WORK/packs"
  git -C "$WORK/packs" init --quiet
  git -C "$WORK/packs" checkout --quiet --orphan packs
  git -C "$WORK/packs" remote add origin "$REMOTE"
fi

DEST="$WORK/packs/$VERSION"
mkdir -p "$DEST/calendar" "$DEST/portal" "$DEST/news"

# 需要外置的文件清单。新增一类资源时在这里加一行，
# 并让对应的读取处改走 ResourcePackStore.readText()
cp app/src/main/assets/calendar/nbu_calendar.json "$DEST/calendar/"
cp app/src/main/assets/portal/entries.json       "$DEST/portal/"
cp app/src/main/assets/news/sources.json         "$DEST/news/"

echo "==> 生成清单"
python - "$WORK/packs" "$VERSION" <<'PY'
import hashlib, io, json, os, sys, datetime

root, version = sys.argv[1], sys.argv[2]
files = []
for dirpath, _, names in os.walk(os.path.join(root, version)):
    for name in sorted(names):
        path = os.path.join(dirpath, name)
        rel = os.path.relpath(path, os.path.join(root, version)).replace(os.sep, "/")
        digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
        files.append({"path": rel, "sha256": digest})
        print(f"    {rel}  {digest[:12]}")

manifest = {
    "version": int(version),
    "updatedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
    # notes 会显示在「调试面板 → 资源包」，写清楚这次改了什么
    "notes": os.environ.get("PACK_NOTES", f"资源包 {version}"),
    # 低于这个 versionCode 的 App 整体跳过该包，不做部分应用
    "minAppVersion": int(os.environ.get("PACK_MIN_APP", "3400")),
    "files": files,
}
with io.open(os.path.join(root, "latest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(f"    latest.json  version={version}  {len(files)} 个文件")
PY

echo "==> 提交并推送"
git -C "$WORK/packs" add -A
git -C "$WORK/packs" commit --quiet -m "pack: 发布资源包 $VERSION"
git -C "$WORK/packs" push --quiet origin packs

echo "==> 完成：packs@$VERSION"
echo "    客户端下次启动 10 秒后会自动取，或到「调试面板 → 资源包 → 立即检查资源包」"
