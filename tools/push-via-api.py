#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""用 Git Data API 把改动提交到 main（本机 git push 不通）。

**为什么要这个脚本**：这台机器直连 GitHub 的 push 通道不可用，只能走 REST API
逐个 blob 提交。

**0.37.20 的事故**：旧版本按 `git status` 取文件，于是「已提交但未推送」的文件
从来不会出现在列表里 —— 学校规则那批文件就这样被静默跳过，产出的包少了资产却不报错。

现在的规则是**两条来源取并集**：
  1. 工作区改动（`git status --porcelain`）
  2. 已提交但远端没有的（`git diff --name-only origin/main..HEAD`）

用法：
    python tools/push-via-api.py --dry-run "提交说明"   # 只打印待传列表
    python tools/push-via-api.py "提交说明"             # 真正提交

令牌来源：环境变量 GITHUB_TOKEN，或本机 %TEMP%\\cred.txt 里的 `password=` 一行。
"""
import json
import os
import subprocess
import sys
import time
import base64

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OWNER, REPO = "LinkUNIv", "nbu-assistant-lite"
API = f"https://api.github.com/repos/{OWNER}/{REPO}"


def token():
    env = os.environ.get("GITHUB_TOKEN")
    if env:
        return env.strip()
    for path in (os.path.join(os.environ.get("TEMP", r"C:\tmp"), "cred.txt"), r"C:\tmp\cred.txt"):
        try:
            with open(path, encoding="utf8") as f:
                for line in f:
                    if line.startswith("password="):
                        return line.split("=", 1)[1].strip()
        except OSError:
            continue
    raise SystemExit("找不到令牌：设置 GITHUB_TOKEN，或放到 %TEMP%\\cred.txt")


TOKEN = token()


def curl(method, path, payload=None):
    cmd = ["curl", "-sS", "--ssl-no-revoke", "-X", method, f"{API}{path}",
           "-H", f"Authorization: token {TOKEN}",
           "-H", "Accept: application/vnd.github+json",
           "-H", "Content-Type: application/json",
           "-H", "User-Agent: nbu-lite-release"]
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf8")
        cmd += ["--data-binary", "@-"]
    out = ""
    for attempt in range(4):
        p = subprocess.run(cmd, input=body, capture_output=True)
        out = p.stdout.decode("utf8", "replace")
        if p.returncode == 0 and out.strip():
            break
        print(f"  重试 {attempt + 1}/4 {method} {path}: {p.stderr.decode('utf8', 'replace')[:80]}")
        time.sleep(2)
    if p.returncode != 0:
        raise SystemExit(f"curl 失败 {method} {path}: {p.stderr.decode('utf8', 'replace')}")
    try:
        return json.loads(out)
    except Exception:
        raise SystemExit(f"返回非 JSON {method} {path}: {out[:400]}")


def git(*args):
    return subprocess.check_output(["git", *args], cwd=PROJ, text=True)


def expand(path):
    """未跟踪目录在 status 里只是 `dir/`，要展开成里面的文件，否则会被跳过。"""
    full = os.path.join(PROJ, path.replace("/", os.sep))
    if not os.path.isdir(full):
        return [path]
    out = []
    for root, _dirs, files in os.walk(full):
        for name in files:
            rel = os.path.relpath(os.path.join(root, name), PROJ).replace(os.sep, "/")
            out.append(rel)
    return out


def changed_files():
    """工作区改动 ∪ 已提交但未推送的改动。"""
    paths = []
    for line in git("status", "--porcelain").splitlines():
        if not line.strip():
            continue
        raw = line[3:]
        if " -> " in raw:
            raw = raw.split(" -> ", 1)[1]
        for p in expand(raw.strip().strip('"')):
            if p not in paths:
                paths.append(p)

    # 这一条就是 0.37.20 缺资产的根因：没有它，已提交的文件永远不会被上传
    try:
        git("fetch", "origin", "main", "--depth=1")
        unpushed = git("diff", "--name-only", "origin/main..HEAD").splitlines()
    except subprocess.CalledProcessError as e:
        print(f"  警告：无法比对 origin/main（{e}），已提交未推送的文件可能被漏掉")
        unpushed = []
    for p in unpushed:
        if p.strip() and p.strip() not in paths:
            paths.append(p.strip())
            print(f"  + 已提交未推送：{p.strip()}")
    return paths


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    msg = args[0] if args else "chore: 更新"

    paths = changed_files()
    print(f"待提交 {len(paths)} 个文件")
    for p in paths:
        print("  ", p)
    if dry:
        print("（dry-run：未提交）")
        return

    ref = curl("GET", "/git/ref/heads/main")
    head = ref["object"]["sha"]
    base_tree = curl("GET", f"/git/commits/{head}")["tree"]["sha"]
    print(f"基于 {head[:7]}")

    tree = []
    for rel in paths:
        full = os.path.join(PROJ, rel.replace("/", os.sep))
        if not os.path.exists(full):
            print(f"  跳过（已删除）：{rel}")
            continue
        if not os.path.isfile(full):
            continue
        with open(full, "rb") as f:
            raw = f.read()
        try:
            content = raw.decode("utf8")
            blob = curl("POST", "/git/blobs", {"content": content, "encoding": "utf-8"})
        except UnicodeDecodeError:
            blob = curl("POST", "/git/blobs", {"content": base64.b64encode(raw).decode(), "encoding": "base64"})
        if "sha" not in blob:
            raise SystemExit(f"  !! blob 失败 {rel}: {str(blob)[:200]}")
        tree.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print(f"  blob {blob['sha'][:7]}  {rel}")

    new_tree = curl("POST", "/git/trees", {"base_tree": base_tree, "tree": tree})
    commit = curl("POST", "/git/commits", {"message": msg, "tree": new_tree["sha"], "parents": [head]})
    print(f"新提交 {commit['sha'][:7]}")
    curl("PATCH", "/git/refs/heads/main", {"sha": commit["sha"], "force": False})
    print("main 已更新")
    print(commit["sha"])


if __name__ == "__main__":
    main()
