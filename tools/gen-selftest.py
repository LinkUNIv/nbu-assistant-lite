#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从精确坐标导出生成**脱敏**的自检夹具，并写出 selftest.json。

用法：
    python tools/gen-selftest.py scmzu \
        --src C:/tmp/scmzu/exact.tsv \
        --expect 18 \
        --must 法律逻辑学 "人工智能与计算思维(实验)" 体育1

输出：
    pack-src/fixtures/<id>.tsv
    pack-src/schools/<id>/selftest.json

脱敏规则（宁可过度）：
    - 三字中文姓名 → 教师甲/乙/丙…（按出现顺序）
    - 学号/工号（≥8 位数字）→ *
    - 任何 URL → 删除
"""
import argparse, io, json, os, re, sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NAME = re.compile(r"(?<![\u4e00-\u9fa5])([\u4e00-\u9fa5]{2,3})(?=,|，|、|$)")
NUM = re.compile(r"\d{8,}")
URL = re.compile(r"https?://\S+")

# 这些整串中文不是姓名，别误脱敏（"同学课表"曾被替换成"教师乙"）
STOP = {
    "同学课表", "课程名称", "教学周次", "上课老师", "未安排时间课程", "报表打印页面",
    "节次", "备注", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("school")
    ap.add_argument("--src", required=True)
    ap.add_argument("--expect", type=int, required=True)
    ap.add_argument("--must", nargs="*", default=[])
    ap.add_argument("--must-not", nargs="*", default=["号楼", "学年第一学期"])
    args = ap.parse_args()

    rows = [l.rstrip("\n").split("\t") for l in io.open(args.src, encoding="utf-8") if l.strip()]
    pool, names = {}, []

    def pseudo(name):
        if name not in pool:
            pool[name] = "教师" + "甲乙丙丁戊己庚辛壬癸"[len(pool) % 10]
        return pool[name]

    out = []
    for r in rows:
        text = URL.sub("", r[3]).strip()
        if not text:
            continue
        text = NUM.sub("*", text)
        # 姓名清单：`陈蒙,余涛,涂少彬` 这种逗号串里的每一项都要换
        def scrub_list(m):
            return m.group(0).replace(m.group(0), ",".join(
                pseudo(t) if re.fullmatch(r"[\u4e00-\u9fa5]{2,4}", t) and t not in STOP else t
                for t in re.split(r"([,，、])", m.group(0))
            ))
        text = re.sub(r"(?:[\u4e00-\u9fa5]{2,4}\s*[,，、]\s*)+[\u4e00-\u9fa5]{2,4}", scrub_list, text)
        # 只对"像姓名"的短中文串替换，避免误伤课名
        if re.fullmatch(r"[\u4e00-\u9fa5]{2,4}", text) and text not in STOP:
            text = pseudo(text)
        out.append(f"{r[0]}\t{r[1]}\t{r[2]}\t{text}")

    fx = os.path.join(PROJ, "pack-src", "fixtures", f"{args.school}.tsv")
    os.makedirs(os.path.dirname(fx), exist_ok=True)
    io.open(fx, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")

    meta = {
        "fixture": f"fixtures/{args.school}.tsv",
        "expectSlots": args.expect,
        "mustContain": args.must,
        "mustNotContain": args.must_not,
    }
    target = os.path.join(PROJ, "pack-src", "schools", args.school, "selftest.json")
    io.open(target, "w", encoding="utf-8", newline="\n").write(json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
    print(f"夹具 {len(out)} 行 → {fx}")
    print(f"清单 → {target}（期望 {args.expect} 条，脱敏 {len(pool)} 个姓名）")


if __name__ == "__main__":
    main()
