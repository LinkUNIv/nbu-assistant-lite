# -*- coding: utf-8 -*-
"""在 PC 上复现 App 的 PDF 解析流程，用于快速迭代。

数据来源：tools/dump_pdf_words.py 用 pymupdf 导出的精确词框（exact.tsv）。
先按行合并成"PDFBox 风格"的片段（App 取到的是 127 段），再跑解析算法。
"""
import io, re, sys

import os
TSV = os.environ.get("SIM_TSV", r"C:////tmp////scmzu////exact.tsv")
CHAR = 9.4

def load_words():
    rows = [l.split("\t") for l in io.open(TSV, encoding="utf-8").read().strip().splitlines()]
    out = []
    for r in rows:
        x, y, t = float(r[1]), -float(r[2]), r[3]   # App 里 y 是负值、越大越靠上
        w = sum(CHAR if ord(c) > 0x2E80 else CHAR * 0.55 for c in t)
        out.append((x, y, t, w))
    return out

def merge_to_lines(words, y_tol=2.5, x_gap=14.0):
    """把同一视觉行、横向相邻的词并成一段（模拟 PDFBox 逐行输出）。"""
    words = sorted(words, key=lambda p: (p[1], p[0]))
    lines, cur, base = [], [], None
    for x, y, t, w in words:
        if base is None or abs(y - base) <= y_tol:
            cur.append((x, y, t, w)); base = y if base is None else base
        else:
            lines.append(cur); cur = [(x, y, t, w)]; base = y
    if cur: lines.append(cur)
    frags = []
    for line in lines:
        line.sort(key=lambda p: p[0])
        buf = []
        for item in line:
            if buf and item[0] - (buf[-1][0] + buf[-1][3]) > x_gap:
                frags.append(tuple(buf)); buf = []
            buf.append(item)
        if buf: frags.append(tuple(buf))
    out = []
    for f in frags:
        x0 = f[0][0]
        x1 = max(i[0] + i[3] for i in f)
        out.append((x0, f[0][1], "".join(i[2] for i in f), x1 - x0))
    return out

if __name__ == "__main__":
    w = load_words()
    f = merge_to_lines(w)
    print("词框", len(w), "→ 合并后", len(f), "段（App 实测 127 段）")
