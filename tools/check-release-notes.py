#!/usr/bin/env python3
"""复跑 CI 的「更新说明一致性」闸。

CI 在发布前会比对两处文字：
  - `CHANGELOG.md` 里该版本的条目
  - `docs/releases/<tag>.md` 的「## 更新内容」小节
归一化（抹掉全部空白）后必须逐字一致，否则构建失败。

本机跑一遍，能在推上去之前发现问题 —— 而不是等 7 分钟 CI 红着回来。

用法：
    python tools/check-release-notes.py v0.38.3-Preview
"""
import re
import sys
import difflib
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent


def changelog_notes(tag: str) -> str:
    """与 .github/workflows/build.yml 里的同名逻辑保持一致。"""
    out, inside = [], False
    path = ROOT / "CHANGELOG.md"
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("## v"):
            if inside:
                break
            inside = stripped[3:].strip().split()[0] == tag
            continue
        if inside:
            out.append(line)
    body = "\n".join(out).strip()
    body = re.sub(r"\n?-{3,}\s*$", "", body).strip()
    # 弹窗标题已经写了版本号，这条在应用里是噪音
    body = "\n".join(l for l in body.splitlines() if not l.strip().startswith("- 版本号提升至"))
    return body.strip()


def section(text: str) -> list[str]:
    out, inside = [], False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("## "):
            if inside:
                break
            inside = stripped[3:].strip() == "更新内容"
            continue
        if inside:
            out.append(line)
    return out


def flatten(lines) -> str:
    # 只比内容不比折行
    return "".join("".join(lines).split())


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    tag = sys.argv[1]

    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    hits = sum(1 for l in changelog.splitlines() if l.startswith(f"## {tag} "))
    if hits != 1:
        print(f"[FAIL] CHANGELOG 里 {tag} 的条应恰好 1 条，实际 {hits} 条")
        return 1

    notes = changelog_notes(tag)
    if not notes:
        print(f"[FAIL] CHANGELOG 里找不到 {tag} 的条目")
        return 1

    doc_path = ROOT / "docs" / "releases" / f"{tag}.md"
    if not doc_path.exists():
        print(f"[FAIL] 缺少发布说明：docs/releases/{tag}.md")
        return 1

    doc = doc_path.read_text(encoding="utf-8")
    got = section(doc)
    want = notes.splitlines()
    if flatten(got) != flatten(want):
        print(f"[FAIL] 更新内容不一致：docs/releases/{tag}.md 与 CHANGELOG.md")
        print("\n".join(difflib.unified_diff(got, want, "release-doc", "CHANGELOG", lineterm="")))
        return 1

    print(f"[OK] {tag}：条目唯一，更新内容一致（{len(notes)} 字）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
