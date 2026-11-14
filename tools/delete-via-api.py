#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""用 Contents API 删除仓库里的文件。

为什么单独一个脚本：Git Data API 的 `git/trees` 传 `sha: null` 在这台机器上
会被拒（`GitRPC::BadObjectState`），而 Contents API 的 DELETE 是稳的。
代价是每个文件一次提交 —— 删除是低频操作，可以接受。

用法：
    python tools/delete-via-api.py docs/releases/v0.38.4-Preview.md app/src/main/assets/schools/xtu/calendar.json
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
BRANCH = "main"


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
    for _ in range(4):
        p = subprocess.run(cmd, input=body, capture_output=True)
        out = p.stdout.decode("utf8", "replace")
        if p.returncode == 0 and out.strip():
            try:
                return json.loads(out)
            except Exception:
                return {"raw": out}
        time.sleep(2)
    raise SystemExit(f"curl 失败 {method} {path}")


def main():
    paths = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not paths:
        print(__doc__)
        return 2
    for rel in paths:
        info = curl("GET", f"/contents/{rel}?ref={BRANCH}")
        sha = info.get("sha") if isinstance(info, dict) else None
        if not sha:
            print(f"  跳过（远端没有）：{rel}")
            continue
        r = curl("DELETE", f"/contents/{rel}", {
            "message": f"chore: 删除 {rel}",
            "sha": sha,
            "branch": BRANCH,
        })
        ok = "commit" in r
        print(f"  {'已删除' if ok else '失败'} {rel}" + ("" if ok else f"  {str(r)[:120]}"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
