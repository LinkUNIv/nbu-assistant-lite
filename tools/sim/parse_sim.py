# -*- coding: utf-8 -*-
import io, re
from load_fragments import load_words, merge_to_lines
import json

RULES = json.load(io.open(r"C:/Users/LinkUniverse/WorkBuddy/风华宁大优化版本/nbu-assistant-lite/pack-src/schools/scmzu/rules.json", encoding="utf-8"))["importer"]["pdf"]

def to_lines(items, tol=3.5):
    items = sorted(items, key=lambda p: -p[1])
    rows, cur = [], []
    for f in items:
        if cur and abs(cur[0][1] - f[1]) <= tol: cur.append(f)
        else:
            if cur: rows.append(cur)
            cur = [f]
    if cur: rows.append(cur)
    return [("".join(f[2] for f in sorted(r, key=lambda p: p[0])), r[0][1]) for r in rows]

def cluster(items, gap):
    blocks, buf, base = [], [], None
    for text, y in to_lines(items):
        if base is not None and abs(base - y) > gap:
            blocks.append((base, "".join(buf))); buf = []
        buf.append(text); base = y
    if buf: blocks.append((base, "".join(buf)))
    return blocks

CODE = re.compile(r"\[[^\]]{1,8}\]")
TIME = re.compile(r"[（(]?\d{1,2}:\d{2}\s*[~\-—]\s*\d{1,2}:\d{2}[)）]?")
# 房间：5号楼 / 15号楼15609 / 体育场馆南湖田径场 / S160211
ROOM = re.compile(r"\d+\s*号楼\s*[A-Za-z]?\d{0,6}|[^\s，,]{0,6}(?:场馆|教室)[^\s，,]{0,10}|S\d{4,}")
SEC = re.compile(r"[（(]\s*第?\s*\d+\s*-\s*\d+\s*节\s*[)）]")
ROOM_TAIL = "楼室庭场馆"

def merge_by_code(blocks, noise):
    out, buf, started = [], [], False
    for _, text in blocks:
        if text.strip() in noise:
            # 课程名称/备注这类行是表格头或页脚，遇到就断
            if started and buf: out.append("".join(buf)); buf = []
            started = False
            continue
        has = CODE.search(text) is not None
        if has and started:
            out.append("".join(buf)); buf = []
        buf.append(text)
        if has: started = True
    if buf: out.append("".join(buf))
    return [s for s in out if s.strip()]

def split_by_section(text):
    marks = list(SEC.finditer(text))
    if len(marks) <= 1: return [text]
    starts = []
    for m in marks:
        codes = list(re.finditer(r"\[[^\]]*\]", text[:m.start()]))
        if not codes: return [text]
        i = codes[-1].start()
        while i > 0:
            c = text[i-1]
            if c.isdigit() or (c.isascii() and c.isalpha()) or c in ROOM_TAIL: break
            i -= 1
        starts.append(i)
    return [text[s:(starts[k+1] if k+1 < len(starts) else len(text))] for k, s in enumerate(starts)]

def run():
    frags = load_words()   # 不合并：解析器自己会按 y/x 重排行
    hdr = sorted([f for f in frags if f[2].startswith("星期")], key=lambda p: p[0])
    centers = [f[0] + f[3]/2 for f in hdr]
    step = (centers[-1] - centers[0]) / (len(centers) - 1)
    left, right = centers[0] - step/2, centers[-1] + step/2
    def col(f):
        cx = f[0] + f[3]/2
        if cx < left or cx > right: return -1
        return min(range(len(centers)), key=lambda i: abs(centers[i] - cx))
    # 列边界：相邻表头中心的中点。跨越边界的长片段要切开，否则一整行会整体落到某一列，
    # 把邻居的内容也带进去（实测「周宪法学[04]…陈光斌,聂长建」就是这么来的）
    bounds = [(centers[i] + centers[i+1]) / 2 for i in range(len(centers) - 1)]

    def split_at_bounds(f):
        x, y, t, w = f
        if not t or w <= 0: return [f]
        per = w / len(t)
        pieces, start, sx = [], 0, x
        for b in bounds:
            if x < b < x + w:
                idx = int(round((b - x) / per))
                if start < idx < len(t):
                    pieces.append((sx, y, t[start:idx], b - sx))
                    start, sx = idx, b
        pieces.append((sx, y, t[start:], x + w - sx))
        return [p for p in pieces if p[2].strip()]

    noise = set(RULES["noiseDrop"])
    body = [f for f in frags if not f[2].startswith(RULES["columnAnchor"]) and f[2].strip() not in noise]
    pat = re.compile(RULES["cellRegex"].replace("(?<", "(?P<"))
    slots, unmatched = [], []
    for c in range(7):
        items = [f for f in body if col(f) == c]
        print(f"列 {c+1}({('一二三四五六日')[c]}) {len(items)} 段")
        for text in merge_by_code(cluster(items, RULES["rowGapPt"]), noise):
            for part in split_by_section(TIME.sub("", text)):
                m = pat.match(part.strip())
                if m:
                    name = m.group("name")
                    name = re.sub(r"^.*?(?:学年第一学期|同学课表|报表打印页[面])", "", name).strip() or name
                    if not name: continue
                    rm = ROOM.search(m.group("room"))
                    room = rm.group(0) if rm else m.group("room")
                    slots.append((c+1, m.group("s1"), m.group("s2"), name[:16], m.group("weeks"), room[:14]))
                else: unmatched.append((c+1, part[:70]))
    print(f"\n解析 {len(slots)} 条")
    for s in slots: print("  ", s)
    print(f"\n未匹配 {len(unmatched)} 条")
    for u in unmatched: print("  ", u)

if __name__ == "__main__":
    run()
