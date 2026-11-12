#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""打标签并发 Release 的入口（本机 git 通道不通，只能走 Git Data API）。

用法：
    python tools/mktag.py <commit-sha> <tag> [说明]

注意：**标签受仓库规则保护，建好后既不能删也不能移**。打之前确认 commit 是对的。
打错了只能新开一个版本号，别想着重打。
"""
import json
import os
import subprocess
import sys
import time

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


def call(method, path, payload=None):
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
        print(f"  重试 {attempt + 1}/4 {method} {path}")
        time.sleep(2)
    try:
        return json.loads(out) if out.strip() else {}
    except Exception:
        raise SystemExit(f"返回非 JSON {method} {path}: {out[:300]}")


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    sha, tag = sys.argv[1], sys.argv[2]
    message = sys.argv[3] if len(sys.argv) > 3 else tag

    created = call("POST", "/git/tags", {
        "tag": tag, "message": message, "object": sha, "type": "commit"
    })
    if "sha" not in created:
        raise SystemExit(f"建标签失败：{created}")
    print(f"标签对象 {created['sha'][:10]}")

    ref = call("POST", "/git/refs", {"ref": f"refs/tags/{tag}", "sha": created["sha"]})
    if ref.get("ref") != f"refs/tags/{tag}":
        raise SystemExit(f"建引用失败：{ref}")
    print(f"已推送 {tag} → 触发 CI 构建发布")
    print(f"发布页：https://github.com/{OWNER}/{REPO}/releases/tag/{tag}")


if __name__ == "__main__":
    main()
