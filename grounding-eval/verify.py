#!/usr/bin/env python3
"""待办接地核查器 —— 判「抽出的待办是否被材料支持」，只打标，绝不删除。

## 为什么只有 MiniCheck 一个验证器

这里曾经是「中文NLI + MiniCheck 双验证器」（一致才放行）。**实测数据把它推翻了**：

  在你真实的 12 条待办上（2026-10-15 实测）：
      只用 MiniCheck : 标记 1 条，真命中 1 条（「参加人工智能面试」——材料实际是
                       「人工智能社团面试」，抽丢了「社团」）            ← 1/1
      只用 中文NLI   : 标记 2 条，真命中 0 条                            ← 0/2
      两者都用       : 标记 3 条，真命中 1 条、误报 2 条                  ← 1/3

  NLI 那 2 条误报是系统性的：材料写「拒绝公式排版内耗！一小时掌握 MathType 公式编辑技巧」
  （一场讲座通知），NLI 坚持认为它推不出「参加」这个动作，于是判 CONTRADICTION。
  它把「材料没明说你会去参加」当成了「不支持」——而流水线要抽的正是「该做什么」。

  另外测过「把译文喂给 NLI」这个设想：NLI 读英文从 28/33 掉到 **18/33**（误报 4→14），
  因为它的词表是中文 BERT（21128），切英文会碎成 st/##ude/##nt。而且那两条争议条目
  在中文、英文下**都**判不支持 —— 跨语言稳定，说明那是 NLI 自己的判断，不是翻译问题。

  在 33 条带标签的测试集上：MiniCheck 32/33（漏报 0），NLI 28/33（漏报 1，且放过了一条
  编造，还给到 p=0.99）。漏报 0 是纠错闸门的底线，所以取 MiniCheck。

## 实测必需的两个细节

  * **译文必须「一份材料 + 它的全部断言」联合翻译**：分开译会让同一活动出现 5 种英文名，
    把 MiniCheck 从 32/33 拖到 28/33。
  * **不能把「有核查程序认为这条没依据」告诉模型**：实测会触发顺从，把正确条目删掉 3/4；
    换成不给任何先验的中立措辞，正确条目 4/4 全保住（4 种措辞结果一致，说明关键在
    「不给负面先验」，不在具体写法）。本脚本只打标不删，就是这条结论的落实。

## 失败必须显式

模型加载不了、LLM 不通、翻译输出缺段 —— 一律报错退出，**绝不静默降级**
（LOCAL-LLM.md 3.2 节记的 GPU 静默降级就是这类坑）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

MINICHECK_DIR = Path(os.environ.get("MINICHECK_DIR", HERE / "ckpt" / "flan-t5-large"))
LLM_API = os.environ.get("LLM_API", "http://127.0.0.1:8080/v1/chat/completions")
LLM_MODEL = "qwen3-4b"

VERDICT_OK = "supported"
VERDICT_FLAG = "unsupported"
FLAG = {VERDICT_OK: "", VERDICT_FLAG: "⚠️ 疑似无依据"}


def read_material(record_path: Path) -> str:
    """bundle（图片 OCR）→ text.md 的正文；file（文本）→ 文件内容。"""
    if record_path.is_dir():
        md = record_path / "text.md"
        if not md.is_file():
            cands = sorted(record_path.glob("*.md"))
            if not cands:
                raise FileNotFoundError(f"{record_path} 下没有 text.md")
            md = cands[0]
        raw = md.read_text(encoding="utf-8")
        m = re.search(r"^##\s*OCR\s*文本\s*$", raw, re.M)
        return (raw[m.end():] if m else raw).strip()
    return record_path.read_text(encoding="utf-8").strip()


def parse_item(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    m = re.match(r"^---\n(.*?)\n---\n", text, re.S)
    if not m:
        raise ValueError(f"{path} 没有 front-matter")
    fm: dict[str, str] = {}
    for line in m.group(1).splitlines():
        if ":" in line and not line.startswith((" ", "\t", "#")):
            k, v = line.split(":", 1)
            fm[k.strip()] = v.strip().strip('"').strip("'")
    fm["_path"] = str(path)
    return fm


class GroundingVerifier:
    """联合翻译 + MiniCheck。翻译和判定分开，任一步失败都抛异常。"""

    def __init__(self) -> None:
        from mc_score import MiniCheckScorer  # noqa: PLC0415
        self.scorer = MiniCheckScorer(str(MINICHECK_DIR))

    @staticmethod
    def _llm(system: str, user: str, timeout: int = 600, max_tokens: int = 2500) -> str:
        payload = {"model": LLM_MODEL,
                   "messages": [{"role": "system", "content": system},
                                {"role": "user", "content": user}],
                   "temperature": 0.0, "max_tokens": max_tokens}
        req = urllib.request.Request(LLM_API, data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            d = json.loads(resp.read().decode())
        return (d["choices"][0]["message"].get("content") or "").strip()

    def translate(self, body: str, claims: list[str]) -> tuple[str, list[str]]:
        """同一材料与它的全部断言放一次调用里译 —— 保证译名一致（实测必需）。"""
        markers = "\n".join(f"===CLAIM {k}===\n<English translation of claim {k}>"
                            for k in range(1, len(claims) + 1))
        listing = "\n".join(f"{k}. {t}" for k, t in enumerate(claims, 1))
        out = self._llm(
            "You are a professional Chinese-to-English translator. Preserve EVERY date, "
            "number, time and proper noun exactly. Use ONE consistent English name for each "
            "Chinese proper noun across the whole output. Output only the requested sections.",
            f"Translate the DOCUMENT and each CLAIM into English.\n\nDocument:\n---\n{body}\n---\n\n"
            f"Claims:\n{listing}\n\nOutput EXACTLY:\n===DOC===\n<translation>\n{markers}")
        pat = re.compile(r"^=*\s*DOC\s*=*$|^=*\s*CLAIM\s*(\d+)\s*=*$", re.M | re.I)
        marks = [(m.start(), m.end(), m.group(1)) for m in pat.finditer(out)]
        if not marks:
            raise ValueError("翻译输出里找不到 ===DOC=== / ===CLAIM n=== 标记")
        doc_en = out[marks[0][1]: marks[1][0] if len(marks) > 1 else len(out)].strip()
        got: dict[int, str] = {}
        for k, (_, end, num) in enumerate(marks[1:]):
            nxt = marks[k + 2][0] if k + 2 < len(marks) else len(out)
            if num:
                got[int(num)] = out[end:nxt].strip()
        missing = [i for i in range(1, len(claims) + 1) if not got.get(i)]
        if missing:
            raise ValueError(f"翻译缺少第 {missing} 条断言")
        if not doc_en:
            raise ValueError("翻译缺少文档正文")
        return doc_en, [got[i] for i in range(1, len(claims) + 1)]

    def judge(self, body: str, claims: list[str]) -> list[dict]:
        doc_en, claims_en = self.translate(body, claims)
        out = []
        for c_zh, c_en in zip(claims, claims_en):
            r = self.scorer.score(doc_en, c_en)
            out.append({"task": c_zh, "supported": r["label"] == 1,
                        "prob": r["prob_supported"], "claim_en": c_en})
        return out


def main() -> int:
    ap = argparse.ArgumentParser(description="核查 todos/items/*.md 的待办是否被材料支持")
    ap.add_argument("--items", required=True, help="todos/items 目录")
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 条（调试用）")
    ap.add_argument("--out", default=str(HERE / "verify_result.jsonl"))
    args = ap.parse_args()

    item_paths = sorted(Path(args.items).glob("*.md"))
    if not item_paths:
        print(f"❌ {args.items} 下没有 .md", file=sys.stderr)
        return 2
    if args.limit:
        item_paths = item_paths[:args.limit]

    print("加载验证器（失败直接报错，不降级）...")
    v = GroundingVerifier()
    print("  ✓ MiniCheck + 联合翻译 就绪\n")

    groups: dict[str, list[dict]] = {}
    for p in item_paths:
        try:
            fm = parse_item(p)
        except Exception as e:  # noqa: BLE001
            print(f"  ! 跳过 {p.name}：{e}", file=sys.stderr)
            continue
        rp = fm.get("record_path", "")
        if not rp or not Path(rp).exists():
            print(f"  ! 跳过 {p.name}：record_path 不存在（{rp}）", file=sys.stderr)
            continue
        groups.setdefault(rp, []).append(fm)

    results = []
    for rp, items in groups.items():
        body = read_material(Path(rp))
        judged = v.judge(body, [it.get("task", "") for it in items])
        print(f"材料 {Path(rp).name}  （{len(items)} 条待办）")
        for it, j in zip(items, judged):
            verdict = VERDICT_OK if j["supported"] else VERDICT_FLAG
            rec = {"task": it.get("task"), "start": it.get("start_date"),
                   "end": it.get("end_date"), "item_path": it["_path"],
                   "record_path": rp, "verdict": verdict, "flag": FLAG[verdict],
                   "prob": round(j["prob"], 3), "claim_en": j["claim_en"]}
            results.append(rec)
            print(f"   {FLAG[verdict] or '✓':14s} {rec['task'][:46]}")
            print(f"        p={j['prob']:.2f}")
        print()

    Path(args.out).write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in results) + "\n", encoding="utf-8")

    flagged = [r for r in results if r["verdict"] == VERDICT_FLAG]
    print("=" * 74)
    print(f"  未标记（MiniCheck 判有依据） {len(results)-len(flagged):>3d}/{len(results)}")
    print(f"  ⚠️ 疑似无依据              {len(flagged):>3d}/{len(results)}")

    # 按支持度升序 —— 复核优先级。低 p 的排前面，你从最可疑的开始看。
    # 注：曾考虑按「6 条路 × 5^p」加权（votes 存在 state.db）。实测本机 12 条待办
    # 全部是 6/6，票数是常量 → votes × 5^p 就是 p 的单调变换，排序与直接按 p 完全一致。
    # 所以这里只按 p 排，省掉读 state.db 的耦合；将来若出现 4/6、6/6 并存的样本，
    # 再把 votes 乘进来才有意义。
    print(f"\n复核优先级（支持度从低到高，从最可疑的看起）：")
    for r in sorted(results, key=lambda x: x["prob"]):
        mark = r["flag"] or "  "
        print(f"  {mark:14s} p={r['prob']:.2f}  {r['task'][:50]}")
    print(f"\n明细：{args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
