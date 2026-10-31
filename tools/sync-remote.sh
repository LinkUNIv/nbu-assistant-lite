#!/usr/bin/env bash
#
# 本机与 GitHub 对齐。
#
# 起因：这台机器上 `git push` 长期不通（CONNECT 被代理拒 / 连接重置），
# 提交只能走 GitHub API 直推。API 直推不会更新本地的 refs，于是本地 HEAD 落后、
# git status 一片脏 —— 看起来像「代码没同步」，其实内容早就推上去了。
#
# 用法：tools/sync-remote.sh
#
# 做什么：
#   1. 带重试地 fetch（网络时通时断，一次失败不代表不通）
#   2. 把当前分支硬重置到 origin/<分支>
#   3. 清掉远端已删除、本地还留着的标签
#
# 前提：本地没有真正未提交的改动。脚本会先检查，有改动就拒绝执行并提示备份。
set -euo pipefail

cd "$(dirname "$0")/.."

BRANCH=$(git rev-parse --abbrev-ref HEAD)

if [ -n "$(git status --porcelain)" ]; then
  echo "工作区有未提交改动，先处理掉再同步：" >&2
  git status --short | head -20 >&2
  echo >&2
  echo "要保留就 git stash；确定不要就 git reset --hard" >&2
  exit 1
fi

echo "==> 拉取 origin/$BRANCH（最多 8 次）"
ok=0
for i in $(seq 1 8); do
  if GIT_TERMINAL_PROMPT=0 timeout 120 git fetch origin "$BRANCH" >/dev/null 2>&1; then
    echo "    第 $i 次成功"
    ok=1
    break
  fi
  echo "    第 $i 次失败，10 秒后重试"
  sleep 10
done
[ "$ok" = "1" ] || { echo "拉取失败：网络仍不通，稍后再试" >&2; exit 1; }

echo "==> 重置到 origin/$BRANCH"
git reset --hard "origin/$BRANCH" | tail -1

echo "==> 清理远端已删除的本地标签"
# fetch --tags 只增不删，必须显式 prune
git fetch --prune --prune-tags origin >/dev/null 2>&1 || true

echo
echo "HEAD=$(git rev-parse --short HEAD)  origin/$BRANCH=$(git rev-parse --short "origin/$BRANCH")"
echo "工作区：$(git status --porcelain | wc -l) 项改动"
