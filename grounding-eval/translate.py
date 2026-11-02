#!/usr/bin/env python3
"""用本地 Qwen3-4B 把材料与断言译成英文，供 MiniCheck（英文模型）使用。

目的：MiniCheck 的 Flan-T5 分词器会把中文断言整条吃成 <unk>，导致它根本读不到断言。
如果中译英质量够高，就存在一条「中文 → 英文 → MiniCheck」的绕行路线。

注意：翻译本身是新引入的一层误差源——日期、数字、人名最容易在翻译中走样，
而 MiniCheck 恰恰主要靠这些 token 下判断。所以这个脚本会把译文原样留存，供人工核对。
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
API = os.environ.get("LLM_API", "http://127.0.0.1:8080/v1/chat/completions")
MODEL = "qwen3-4b"

SYS_DOC = (
    "You are a professional Chinese-to-English translator. "
    "Translate the user's text into English. "
    "Preserve EVERY date, number, time, group name, person name and organization name "
    "EXACTLY as-is. Do not add explanations, notes, summaries or commentary. "
    "Do not translate numeric dates into words. Output only the translation."
)
SYS_CLAIM = SYS_DOC


def call(system: str, user: str, timeout: int = 300) -> tuple[str, str | None]:
    payload = {
        "model": MODEL,
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": user}],
        "temperature": 0.0,
        "max_tokens": 1200,
    }
    req = urllib.request.Request(
        API, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            d = json.loads(resp.read().decode())
        return (d["choices"][0]["message"].get("content") or "").strip(), None
    except urllib.error.HTTPError as e:
        return "", f"HTTP {e.code}: {e.read()[:200].decode(errors='replace')}"
    except Exception as e:  # noqa: BLE001
        return "", f"{type(e).__name__}: {e}"


def main() -> int:
    drafts = {r["item_id"]: r for r in
              (json.loads(l) for l in (HERE / "naive_drafts.jsonl").open(encoding="utf-8"))}
    cases = [json.loads(l) for l in
             (HERE / "minicheck_results.jsonl").open(encoding="utf-8")]

    doc_en: dict[int, str] = {}
    print(f"翻译 {len(drafts)} 份材料 →")
    for i, r in sorted(drafts.items()):
        en, err = call(SYS_DOC, r["body"])
        n_in = len(r["body"])
        if err:
            print(f"  id={i} 翻译失败：{err}")
        else:
            print(f"  id={i} {n_in} 字 → {len(en)} 字符")
            print(f"      {en[:160]}")
        doc_en[i] = en

    claim_en: dict[str, str] = {}
    print(f"\n翻译 {len(cases)} 条断言 →")
    for n, c in enumerate(cases, 1):
        en, err = call(SYS_CLAIM, c["claim"])
        claim_en[c["claim"]] = en
        print(f"  [{n:2d}/{len(cases)}] {c['claim'][:34]:36s} → {en[:70]}")
        if err:
            print(f"        ✗ {err}")

    (HERE / "translations.jsonl").write_text(
        json.dumps({"docs_en": doc_en, "claims_en": claim_en},
                   ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n已写出：{HERE/'translations.jsonl'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
