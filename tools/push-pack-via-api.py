#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把本地资源包推到 `packs` 分支（本机 git push 不通，只能走 Git Data API）。

用法：
    tools/publish-pack.sh --local 202611141300
    python tools/push-pack-via-api.py 202611141300

为什么单独一个脚本：`publish-pack.sh` 的远程模式用 `git clone/push`，
这台机器上 push 长期被 TLS 掐断（`schannel: server closed abruptly`），
而 API 通道一直是通的 —— 与 `push-via-api.py` 同一套机制。

做的事：
  1. 读 `dist/pack/<版本>/latest.json` 与 `<版本>/` 下的文件
  2. 逐个建 blob → 一棵新 tree（基于 packs 当前 tree）
  3. 建 commit → 直接改 `refs/heads/packs`

注意：资源包版本号只升不降，客户端会拒绝比本地更旧的包。
"""
import base64
import json
import os
import subprocess
import sys
import time

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
           "-H", "User-Agent: nbu-lite-pack"]
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


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    version = sys.argv[1]

    root = os.path.join(PROJ, "dist", "pack", version)
    files_dir = os.path.join(root, version)
    manifest = os.path.join(root, "latest.json")
    if not os.path.isdir(files_dir) or not os.path.isfile(manifest):
        raise SystemExit(f"找不到 {root}，先跑：tools/publish-pack.sh --local {version}")

    ref = curl("GET", "/git/ref/heads/packs")
    head = ref["object"]["sha"]
    base_tree = curl("GET", f"/git/commits/{head}")["tree"]["sha"]
    print(f"packs 当前 HEAD={head[:8]}")

    tree = []

    def add(repo_path, local_path):
        raw = open(local_path, "rb").read()
        blob = curl("POST", "/git/blobs", {
            "content": base64.b64encode(raw).decode(), "encoding": "base64"})
        tree.append({"path": repo_path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print(f"  {repo_path}  {blob['sha'][:8]}  {len(raw)}B")

    add("latest.json", manifest)
    for dirpath, _, names in os.walk(files_dir):
        for name in sorted(names):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, files_dir).replace(os.sep, "/")
            add(f"{version}/{rel}", full)

    new_tree = curl("POST", "/git/trees", {"base_tree": base_tree, "tree": tree})
    commit = curl("POST", "/git/commits", {
        "message": f"pack: 发布资源包 {version}",
        "tree": new_tree["sha"],
        "parents": [head],
    })
    curl("PATCH", "/git/refs/heads/packs", {"sha": commit["sha"], "force": False})
    print(f"已推送 packs@{version}  commit={commit['sha'][:8]}")
    print("客户端下次启动 12 秒后自动取，或「调试面板 → 资源包 → 立即检查资源包」")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
