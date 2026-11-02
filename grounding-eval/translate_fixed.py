#!/usr/bin/env python3
"""修译名一致性：把「一份材料 + 它的全部断言」放进同一次调用里翻译。

为什么这样就够了：
  MiniCheck 每次比较的只是 (某份材料, 属于它的某条断言)。跨材料的译名一致无所谓，
  真正会致命的只是**同一份材料内部**文档与断言用了不同译名（上一轮实测：
  材料写 Institute Cup、断言写 Department Cup → p=0.063 被误判）。
  放在同一个上下文里翻译，模型自然沿用同一套译名，不需要术语表，也不需要第二遍。

输出用显式分隔符而不是 JSON：小模型吐的 JSON 转义经常不合法（上一版就栽在这）。
"""
from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
API = os.environ.get("LLM_API", "http://127.0.0.1:8080/v1/chat/completions")
MODEL = "qwen3-4b"

SYSTEM = (
    "You are a professional Chinese-to-English translator. "
    "Translate faithfully and completely. Preserve EVERY date, number, time, group name, "
    "person name and organization name exactly. Do not translate numeric dates into words. "
    "IMPORTANT: use ONE consistent English name for each Chinese proper noun across the whole "
    "output — the document and all claims refer to the same event, so they must use identical "
    "wording. Output only the requested sections with the exact markers, nothing else."
)

USER = """Translate the DOCUMENT and each CLAIM below into English.

Document:
---
{body}
---

Claims:
{claims}

Output EXACTLY in this format and nothing else:
===DOC===
<English translation of the document>
{claim_markers}"""


def call(user: str, timeout: int = 600) -> str:
    payload = {"model": MODEL,
               "messages": [{"role": "system", "content": SYSTEM},
                            {"role": "user", "content": user}],
               "temperature": 0.0, "max_tokens": 2500}
    req = urllib.request.Request(API, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        d = json.loads(resp.read().decode())
    return (d["choices"][0]["message"].get("content") or "").strip()


def split_sections(text: str, n_claims: int) -> tuple[str, list[str]]:
    """按 ===DOC=== / ===CLAIM k=== 切分；容忍模型少写或多写等号。"""
    pat = re.compile(r"^=*\s*DOC\s*=*$|^=*\s*CLAIM\s*(\d+)\s*=*$",
                     re.M | re.I)
    marks = [(m.start(), m.end(), m.group(1)) for m in pat.finditer(text)]
    if not marks:
        return text, []
    doc = text[marks[0][1]: marks[1][0] if len(marks) > 1 else len(text)].strip()
    claims: dict[int, str] = {}
    for k, (_, end, num) in enumerate(marks[1:]):
        nxt = marks[k + 2][0] if k + 2 < len(marks) else len(text)
        if num:
            claims[int(num)] = text[end:nxt].strip()
    return doc, [claims.get(i, "") for i in range(1, n_claims + 1)]


def main() -> int:
    drafts = {r["item_id"]: r for r in
              (json.loads(l) for l in (HERE / "naive_drafts.jsonl").open(encoding="utf-8"))}
    cases = [json.loads(l) for l in
             (HERE / "minicheck_results.jsonl").open(encoding="utf-8")]

    by_item: dict[int, list[str]] = defaultdict(list)
    for c in cases:
        if c["claim"] not in by_item[c["item_id"]]:
            by_item[c["item_id"]].append(c["claim"])

    docs_en, claims_en, failed = {}, {}, []
    print(f"逐份材料联合翻译（{len(drafts)} 次调用）")
    for i, r in sorted(drafts.items()):
        cl = by_item.get(i, [])
        markers = "\n".join(f"===CLAIM {k}===\n<English translation of claim {k}>"
                            for k in range(1, len(cl) + 1))
        claim_list = "\n".join(f"{k}. {t}" for k, t in enumerate(cl, 1))
        out = call(USER.format(body=r["body"], claims=claim_list,
                               claim_markers=markers))
        doc, got = split_sections(out, len(cl))
        ok = doc and all(got)
        print(f"  id={i}  文档 {len(doc)} 字符，断言 {sum(1 for g in got if g)}/{len(cl)}"
              f"  {'✓' if ok else '✗ 解析不全'}")
        if not ok:
            failed.append(i)
        docs_en[i] = doc or r["body"]
        for t, g in zip(cl, got):
            claims_en[t] = g or t

    # ---- 验证：同一材料内，专名只有一个英文写法 ----
    probes = ["新生杯", "院系杯", "福昕PDF", "帆软杯"]
    variants = {
        "新生杯": ["New Student Cup", "Newcomer Cup", "New Generation Cup",
                   "New Life Cup", "Shengxin Cup", "Freshman Cup"],
        "院系杯": ["Institute Cup", "Department Cup", "School Cup", "Faculty Cup"],
        "福昕PDF": ["Foxit PDF", "Fu Shen PDF", "Fuxin PDF", "Foxit"],
        "帆软杯": ["Fane Soft Cup", "Fanrong Cup", "Fanruan Cup", "FineSoft Cup"],
    }
    print("\n验证：每份材料内「文档 + 它的断言」的译名写法")
    bad = 0
    for i, r in sorted(drafts.items()):
        scoped = docs_en[i] + "\n" + "\n".join(claims_en.get(t, "") for t in by_item.get(i, []))
        for zh in probes:
            if zh not in r["body"]:
                continue
            present = sorted({v for v in variants[zh] if v in scoped})
            if len(present) > 1:
                bad += 1
                print(f"  ✗ id={i} {zh}: {present}")
            elif present:
                print(f"  ✓ id={i} {zh}: {present[0]}")
    print(f"\n{'同一材料内译名全部一致 ✓' if bad == 0 else f'{bad} 处仍不一致 ✗'}")
    if failed:
        print(f"解析不全的材料（已回落原文）：{failed}")

    (HERE / "translations_fixed.jsonl").write_text(json.dumps(
        {"docs_en": docs_en, "claims_en": claims_en,
         "note": "同一材料与其断言联合翻译"}, ensure_ascii=False, indent=1),
        encoding="utf-8")
    print(f"已写出：{HERE/'translations_fixed.jsonl'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
