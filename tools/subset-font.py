#!/usr/bin/env python
"""把 Noto Serif 裁成「只含数字与破折号」的子集，产物直接放 res/font。

为什么要内嵌：`FontFamily.Serif` 不是确定值，由 ROM 决定 —— 小米是自家字体、
AOSP 是 Noto Serif、个别 ROM 的 Serif 还会回退成 Roboto（数字直接变无衬线）。
只有把字体文件打进包里，所有机型才是同一份字形。

用法：

    python tools/subset-font.py path/to/NotoSerif-Regular.ttf

依赖：`pip install fonttools`（不需要 brotli，产物是裸 TTF）

字符集是**写死**的：`ui/nav/ProgressCapsule` 的数字槽只会渲染 0-9 与「—」。
新增字符时必须同步改 CHARS，否则上屏会出豆腐块 —— 这类问题在真机上很难一眼发现，
因为缺字是"静默回退到系统字体"，不是报错。
"""

import pathlib
import sys

CHARS = "0123456789—"

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "app" / "src" / "main" / "res" / "font"
OUT_NAME = "noto_serif_subset.ttf"


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 1
    src = pathlib.Path(argv[1])
    if not src.exists():
        print(f"找不到源字体：{src}")
        return 1

    raw = src.read_bytes()[:4]
    if raw not in (b"\x00\x01\x00\x00", b"true", b"OTTO"):
        print(f"{src} 不是 TrueType/OpenType（头 {raw.hex()}）。"
              "若开头有 8 字节长度头，说明是被代理改写过的下载结果，换一个干净来源。")
        return 1

    from fontTools import subset

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / OUT_NAME
    args = [
        str(src),
        f"--text={CHARS}",
        f"--output-file={out}",
        "--layout-features=",
        "--no-hinting",
        "--desubroutinize",
        "--drop-tables+=DSIG",
    ]
    subset.main(args)
    print(f"已生成 {out}（{out.stat().st_size / 1024:.1f} KB，字符集 {CHARS}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
