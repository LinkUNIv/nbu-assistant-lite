#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""发布**公告独立通道**（`packs` 分支的 `release/announcements.json`）。

用法：
    python tools/publish-announcements.py                # 版本号取当前时间
    python tools/publish-announcements.py 202611220200   # 指定版本号

为什么单独一条通道：公告挂在资源包上时，整包 `minAppVersion` 一抬，公告就对旧版
连带失效；反过来公告想用新字段又怕旧版误读。拆开之后互不牵连——旧版根本不请求这个文件。

文件格式：
    { "version": 202611220200, "updatedAt": "...", "announcements": [ ... ] }

客户端只在 `version` **大于**本地缓存时才应用，所以重发同版本号是空操作。
"""
import base64
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib.machinery import SourceFileLoader

_impl = SourceFileLoader("push_pack", os.path.join(os.path.dirname(os.path.abspath(__file__)), "push-pack-via-api.py")).load_module()
curl = _impl.curl

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(PROJ, "pack-src", "announcements.json")
REMOTE_PATH = "release/announcements.json"


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else time.strftime("%Y%m%d%H%M")
    if not (len(version) == 12 and version.isdigit()):
        raise SystemExit(f"版本号必须是 12 位 yyyyMMddHHmm，收到：{version}")
    if not os.path.isfile(SRC):
        raise SystemExit(f"找不到 {SRC}")

    src = json.load(open(SRC, encoding="utf-8"))
    if not src.get("announcements"):
        raise SystemExit("announcements 为空，拒绝发布（避免把线上公告清空）")

    payload = {
        "version": int(version),
        "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%S+08:00"),
        "announcements": src["announcements"],
    }
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"

    head = curl("GET", "/git/ref/heads/packs")["object"]["sha"]
    base_tree = curl("GET", f"/git/commits/{head}")["tree"]["sha"]
    blob = curl("POST", "/git/blobs", {
        "content": base64.b64encode(text.encode("utf-8")).decode(), "encoding": "base64"})
    tree = curl("POST", "/git/trees", {
        "base_tree": base_tree,
        "tree": [{"path": REMOTE_PATH, "mode": "100644", "type": "blob", "sha": blob["sha"]}],
    })
    commit = curl("POST", "/git/commits", {
        "message": f"announce: 发布公告 {version}（{len(src['announcements'])} 条）",
        "tree": tree["sha"],
        "parents": [head],
    })
    curl("PATCH", "/git/refs/heads/packs", {"sha": commit["sha"], "force": False})
    print(f"已推送 {REMOTE_PATH}  version={version}  {len(text)}B  commit={commit['sha'][:8]}")
    print("客户端下次启动 12 秒后取；旧版不请求该文件，互不影响。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
