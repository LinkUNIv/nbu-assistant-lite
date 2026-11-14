#!/usr/bin/env bash
#
# 发布一个资源包。
#
# 用法：
#   tools/publish-pack.sh                     # 远程：版本号取当前时间 yyyyMMddHHmm
#   tools/publish-pack.sh 202611141300        # 远程：指定版本号
#   tools/publish-pack.sh --local             # 本地：只生成目录，不推仓库（测试用）
#
# 做什么：
#   1. 从 `pack-src/` 取数据文件（**不是** assets —— 0.38.4 起 APK 里只剩空壳）
#   2. 计算每个文件的 sha256，写进 `latest.json`
#   3. 远程模式：提交并推送到 `packs` 分支；本地模式：落到 `dist/pack/<版本>/`
#
# 为什么数据不在 assets：这一版要验证「内容全部由资源包驱动」。
# 如果 APK 里也留一份，就分不清界面上看到的是包还是内置。
#
# 目录约定（客户端按此读取）：
#   packs/latest.json            清单
#   packs/<version>/<path>       文件，path 与逻辑路径一致
#
# 版本号规则：`yyyyMMddHHmm`，12 位数字，**只升不降**，同号重发会被客户端忽略。
set -euo pipefail

cd "$(dirname "$0")/.."

MODE="remote"
if [ "${1:-}" = "--local" ]; then
  MODE="local"
  shift
fi

VERSION="${1:-$(date +%Y%m%d%H%M)}"
if ! [[ "$VERSION" =~ ^[0-9]{12}$ ]]; then
  echo "版本号必须是 12 位 yyyyMMddHHmm，收到：$VERSION" >&2
  exit 1
fi

SRC="pack-src"
if [ ! -d "$SRC" ]; then
  echo "找不到内容源目录 $SRC" >&2
  exit 1
fi

echo "==> 内容源：$SRC"
(cd "$SRC" && find . -type f | sed 's|^\./||' | sort | sed 's/^/    /')

# 两种模式都先在 dist/pack/<版本>/ 里生成一份，好处是 push 失败时可以直接改用 API
ROOT="$PWD/dist/pack/$VERSION"
rm -rf "$ROOT"
mkdir -p "$ROOT/$VERSION"
(cd "$SRC" && tar -cf - .) | tar -xf - -C "$ROOT/$VERSION"
DEST="$ROOT/$VERSION"
echo "==> 生成目录：$ROOT"

echo "==> 生成清单"
python - "$ROOT" "$VERSION" <<'PY'
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
    "notes": os.environ.get("PACK_NOTES", f"资源包 {version}"),
    "minAppVersion": int(os.environ.get("PACK_MIN_APP", "3804")),
    "files": files,
}
with io.open(os.path.join(root, "latest.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(f"    latest.json  version={version}  {len(files)} 个文件")
PY

if [ "$MODE" = "local" ]; then
  echo
  echo "本地包已生成，未推送仓库。装进调试版："
  echo "  bash tools/push-pack-local.sh $VERSION"
  exit 0
fi

echo "==> 推送到 packs 分支"
git -C "$ROOT" init --quiet 2>/dev/null || true

# 这台机器上 git push 长期被 TLS 掐断（schannel: server closed abruptly），
# 而 API 通道是通的 —— 先试 git，失败立刻改用 push-pack-via-api.py，不让发布卡在半路。
REMOTE=$(git remote get-url origin)
if git -C "$ROOT" ls-remote "$REMOTE" refs/heads/packs >/dev/null 2>&1; then
  set +e
  git -C "$ROOT" add -A
  git -C "$ROOT" commit --quiet -m "pack: 发布资源包 $VERSION" 2>/dev/null
  git -C "$ROOT" push --quiet origin packs 2>/dev/null
  PUSHED=$?
  set -e
  if [ "$PUSHED" = "0" ]; then
    echo "==> 完成（git）：packs@$VERSION"
    exit 0
  fi
  echo "    git push 失败（$PUSHED），改用 API 直推"
fi

python tools/push-pack-via-api.py "$VERSION"
