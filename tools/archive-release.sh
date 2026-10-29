#!/usr/bin/env bash
# 出包留档：把指定变体的 APK + 源码快照归档到 dist/archive/v<版本>-<通道>/
#
# 用法：tools/archive-release.sh [preview|debug] [-f]
#   preview  默认。预览版，包名 app.nbult.lite.preview，版本名带 -Preview
#   debug    调试版，包名 app.nbult.lite.debug，版本名带 -Debug
#   -f       覆盖已存在的同名归档
#
# 正式版（app.nbult.lite，无后缀）当前**不出包**：等 1.0.0 正式构建并上传 GitHub 之后再走，
# 届时再给这个脚本加 release 分支与正式签名配置。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VARIANT="${1:-preview}"
if [ "${VARIANT}" = "-f" ] || [ -z "${VARIANT}" ]; then
  VARIANT="preview"
fi
FORCE="${2:-}"

case "$VARIANT" in
  preview)
    SUFFIX="-Preview"
    PKG="app.nbult.lite.preview"
    LABEL="预览版"
    ;;
  debug)
    SUFFIX="-Debug"
    PKG="app.nbult.lite.debug"
    LABEL="调试版"
    ;;
  *)
    echo "未知变体：$VARIANT（只支持 preview / debug）" >&2
    exit 2
    ;;
esac

VERSION_BASE="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
VERSION="${VERSION_BASE}${SUFFIX}"

if [ -z "$VERSION_BASE" ]; then
  echo "无法从 app/build.gradle.kts 读取 versionName" >&2
  exit 1
fi

APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
if [ ! -f "$APK" ]; then
  echo "未找到 APK，请先构建：./gradlew :app:assemble$(echo "${VARIANT^}")" >&2
  exit 1
fi

ARCHIVE="dist/archive/v$VERSION"
if [ -d "$ARCHIVE" ] && [ "$FORCE" != "-f" ]; then
  echo "归档已存在：$ARCHIVE（如需覆盖请加 -f）" >&2
  exit 1
fi
mkdir -p "$ARCHIVE"

APK_NAME="nbu-assistant-lite-$VERSION.apk"
cp "$APK" "$ARCHIVE/$APK_NAME"

# R8 的 mapping 必须随包归档：没有它，这个包报上来的崩溃堆栈全是 a.a.a 这样的混淆名，
# 等于白丢一次取证机会。release/preview 开了 minify，debug 没有，所以这里判断文件存在才复制。
MAPPING="app/build/outputs/mapping/$VARIANT/mapping.txt"
MAPPING_NOTE=""
if [ -f "$MAPPING" ]; then
  cp "$MAPPING" "$ARCHIVE/mapping-$VARIANT.txt"
  MAPPING_NOTE="mapping-$VARIANT.txt"
  echo "已附混淆映射：mapping-$VARIANT.txt（$(du -h "$MAPPING" | cut -f1)）"
fi
# dist/ 只留各通道最新一版，归档目录里保留全部历史
rm -f "dist/nbu-assistant-lite-$VERSION_BASE-$VARIANT.apk"
cp "$APK" "dist/$APK_NAME"

SHA="$(sha256sum "$ARCHIVE/$APK_NAME" | cut -d' ' -f1)"
SIZE="$(du -h "$ARCHIVE/$APK_NAME" | cut -f1)"
BUILD_TIME="$(date '+%Y-%m-%d %H:%M:%S')"

python - "$ARCHIVE" "$VERSION" <<'PY'
import os
import sys
import zipfile

archive, version = sys.argv[1], sys.argv[2]
root = os.getcwd()
skip_dirs = {'.gradle', 'build', '.git', 'dist', '.idea'}
out = os.path.join(archive, f"source-v{version}.zip")

with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zf:
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in skip_dirs]
        for name in files:
            path = os.path.join(base, name)
            rel = os.path.relpath(path, root)
            if rel == 'local.properties' or rel.startswith('dist' + os.sep):
                continue
            zf.write(path, rel)
print(f"源码快照：{out}")
PY

cat > "$ARCHIVE/RELEASE.md" <<EOF
# 宁大助手 Lite v$VERSION（$LABEL）

> 这是**测试包**，不是正式版。正式版包名为 \`app.nbult.lite\`，要到 1.0.0 才发。

- 版本号：$VERSION（versionCode $VERSION_CODE）
- 构建类型：$VARIANT
- 构建时间：$BUILD_TIME
- 包名：$PKG
- APK：$APK_NAME（$SIZE）
- SHA-256：$SHA

## 本版变更

见项目根目录 \`CHANGELOG.md\` 中 v$VERSION 段落。

## 归档内容

- \`$APK_NAME\` — 可直接安装的$LABEL
- \`source-v$VERSION.zip\` — 源码快照（排除 build / .gradle / dist）
${MAPPING_NOTE:+- $MAPPING_NOTE — R8 混淆映射，解混淆崩溃堆栈时用 retrace}
EOF

echo "已归档 v$VERSION（versionCode $VERSION_CODE，$LABEL）→ $ARCHIVE"
