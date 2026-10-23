#!/usr/bin/env bash
# 出包留档：把当前 debug APK + 源码快照归档到 dist/archive/v<版本>/
# 用法：tools/archive-release.sh [-f]
#   -f  覆盖已存在的同名归档

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"

if [ -z "$VERSION" ]; then
  echo "无法从 app/build.gradle.kts 读取 versionName" >&2
  exit 1
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "未找到 APK，请先构建：./gradlew :app:assembleDebug" >&2
  exit 1
fi

ARCHIVE="dist/archive/v$VERSION"
if [ -d "$ARCHIVE" ] && [ "${1:-}" != "-f" ]; then
  echo "归档已存在：$ARCHIVE（如需覆盖请加 -f）" >&2
  exit 1
fi
mkdir -p "$ARCHIVE"

APK_NAME="nbu-assistant-lite-$VERSION-debug.apk"
cp "$APK" "$ARCHIVE/$APK_NAME"
rm -f dist/nbu-assistant-lite-*-debug.apk
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
# 宁大助手 Lite v$VERSION

- 版本号：$VERSION（versionCode $VERSION_CODE）
- 构建类型：debug
- 构建时间：$BUILD_TIME
- 包名：app.nbult.lite.debug
- APK：$APK_NAME（$SIZE）
- SHA-256：$SHA

## 本版变更

见项目根目录 \`CHANGELOG.md\` 中 v$VERSION 段落。

## 归档内容

- \`$APK_NAME\` — 可直接安装的调试包
- \`source-v$VERSION.zip\` — 源码快照（排除 build / .gradle / dist）
EOF

echo "已归档 v$VERSION（versionCode $VERSION_CODE）→ $ARCHIVE"
