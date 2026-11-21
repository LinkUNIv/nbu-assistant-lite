#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 PDF 的**带坐标文字**导出来，供解析器调参和单测使用。

为什么需要这个：几何算法（归列、行聚类、网格切格）最容易写错，而改一次 Kotlin
要等两分钟 CI。有了它就能在 PC 上拿到与 App 同构的坐标，先在 Python/单测里把
版式跑通，再写规则。

输出与 `app/src/test/resources/pdf/*_fragments.tsv` 完全一致：
    page <TAB> x <TAB> y <TAB> text
其中 y 是**自上而下**的距离（PDFBox 的 yDirAdj 口径），测试加载时会取反。

用法：
    # 看结构（有没有文字层、多少片段、页面尺寸）
    python tools/dump_pdf_words.py 报表.pdf

    # 直接生成单测夹具
    python tools/dump_pdf_words.py 报表.pdf --fixture app/src/test/resources/pdf/scmzu_fragments.tsv

    # 额外导出表格线（细矩形），供 cellSource=lines 使用
    python tools/dump_pdf_words.py 报表.pdf --rules /tmp/scmzu_rules.tsv

⚠️ 输出的文字是**原文**，含姓名/学号。当夹具入库前必须脱敏（换成人名占位、删学号）。

只依赖 pypdf（本机已有）。宽度是按字号估算的：中文 1em、其它 0.5em ——
这与 App 里 `estimatedWidth()` 的口径一致，所以夹具上的行为与真机接近。
"""

import argparse
import sys
from pathlib import Path

try:
    from pypdf import PdfReader
except ImportError:
    sys.exit("缺少 pypdf：pip install pypdf")

CJK_THRESHOLD = 0x2E80


def estimate_width(text: str, font_size: float) -> float:
    """中文按 1em、其它按 0.5em 估宽（与 App 的 estimatedWidth 同口径）。"""
    units = sum(1.0 if ord(ch) > CJK_THRESHOLD else 0.5 for ch in text)
    return units * font_size


def collect_fragments(page, page_index: int):
    """返回 [(x, y_top, width, text), ...]，坐标单位为 pt。"""
    height = float(page.mediabox.height)
    out = []

    def visitor(text, cm, tm, font_dict, font_size):
        trimmed = text.strip()
        if not trimmed:
            return
        x = tm[4]
        # 带翻转矩阵的 PDF（本文件是 .24 0 0 -.24 ...）里，tm[5] 已经是"距顶部"；
        # 普通 PDF 才需要 height - y。判据看 CTM 的 d 分量符号。
        flipped = cm[3] < 0
        y_top = tm[5] if flipped else height - tm[5]
        out.append((x, y_top, estimate_width(trimmed, font_size or 10.0), trimmed))

    page.extract_text(visitor_text=visitor)
    return out


def collect_rules(page, page_index: int):
    """从内容流里取细矩形/线段，输出为表格线坐标（自上而下）。"""
    height = float(page.mediabox.height)
    data = page.get_contents().get_data().decode("latin1")
    tokens = data.split()
    numbers, stack, rects = [], [], []
    ctm = (1, 0, 0, 1, 0, 0)

    def multiply(a, b):
        a1, b1, c1, d1, e1, f1 = a
        a2, b2, c2, d2, e2, f2 = b
        return (a1 * a2 + b1 * c2, a1 * b2 + b1 * d2,
                c1 * a2 + d1 * c2, c1 * b2 + d1 * d2,
                e1 * a2 + f1 * c2 + e2, e1 * b2 + f1 * d2 + f2)

    for token in tokens:
        try:
            numbers.append(float(token))
            continue
        except ValueError:
            pass
        if token == "q":
            stack.append(ctm)
        elif token == "Q":
            if stack:
                ctm = stack.pop()
        elif token == "cm" and len(numbers) >= 6:
            ctm = multiply(tuple(numbers[-6:]), ctm)
        elif token == "re" and len(numbers) >= 4:
            rects.append((*numbers[-4:], ctm))
        numbers = []

    lines = []
    for x, y, w, h, cm in rects:
        a, b, c, d, e, f = cm
        x1, y1 = a * x + c * y + e, b * x + d * y + f
        x2, y2 = a * (x + w) + c * (y + h) + e, b * (x + w) + d * (y + h) + f
        left, right = min(x1, x2), max(x1, x2)
        bottom, top = min(y1, y2), max(y1, y2)
        width, height_px = right - left, top - bottom
        is_thin = min(width, height_px) <= 2.5 and max(width, height_px) >= 15
        if is_thin:
            # 与文字同一套坐标：y 取"距顶部"，再取负（App 内 PdfRule 与 TextFragment 都用负值向上）
            lines.append((left, -(height - top), right, -(height - bottom)))
    return lines


def main():
    parser = argparse.ArgumentParser(description="导出 PDF 的带坐标文字")
    parser.add_argument("pdf", type=Path, help="输入 PDF")
    parser.add_argument("--fixture", type=Path, help="把文字片段写成单测夹具 TSV")
    parser.add_argument("--rules", type=Path, help="把表格线写成 TSV（x1 y1 x2 y2）")
    parser.add_argument("--preview", type=int, default=0, help="在终端预览前 N 行")
    args = parser.parse_args()

    if not args.pdf.exists():
        sys.exit(f"文件不存在: {args.pdf}")

    reader = PdfReader(str(args.pdf))
    print(f"输入: {args.pdf}")
    print(f"页数: {len(reader.pages)}")

    all_fragments = []
    for index, page in enumerate(reader.pages):
        fragments = collect_fragments(page, index)
        all_fragments.extend((index, *f) for f in fragments)
        print(f"  第{index + 1}页: {len(fragments)} 段文字, "
              f"尺寸 {float(page.mediabox.width):.0f}x{float(page.mediabox.height):.0f} pt")

    total_chars = sum(len(f[4]) for f in all_fragments)
    print(f"合计: {len(all_fragments)} 段 / {total_chars} 字")
    if not all_fragments:
        print("⚠️ 这份 PDF 没有文字层 —— 走 App 时会被判为扫描件，改走识图")
        return

    if args.preview:
        print("-" * 60)
        for index, x, y, width, text in all_fragments[: args.preview]:
            print(f"  p{index} x={x:7.1f} y={y:7.1f} w={width:5.1f}  {text[:40]}")

    if args.fixture:
        args.fixture.parent.mkdir(parents=True, exist_ok=True)
        lines = [f"{i}\t{x:.1f}\t{y:.1f}\t{text}" for i, x, y, _w, text in all_fragments]
        args.fixture.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"夹具已写出: {args.fixture}（{len(lines)} 行）")

    if args.rules:
        rules = []
        for index, page in enumerate(reader.pages):
            for x1, y1, x2, y2 in collect_rules(page, index):
                rules.append(f"{index}\t{x1:.2f}\t{y1:.2f}\t{x2:.2f}\t{y2:.2f}")
        args.rules.parent.mkdir(parents=True, exist_ok=True)
        args.rules.write_text("\n".join(rules) + "\n", encoding="utf-8")
        print(f"表格线已写出: {args.rules}（{len(rules)} 条）")


if __name__ == "__main__":
    main()
