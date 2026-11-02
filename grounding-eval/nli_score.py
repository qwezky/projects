#!/usr/bin/env python3
"""用中文 NLI 模型（Erlangshen-Roberta-330M-NLI）直接判中文「材料是否支持断言」。

与 MiniCheck 的关键差别：
  * 词表是中文 BERT（21128），中文按字切 → 不会像 Flan-T5 那样把整条断言吃成 <unk>；
  * 输出是 3 类：CONTRADICTION=0 / NEUTRAL=1 / ENTAILMENT=2；
  * 判据：argmax == ENTAILMENT 才算「有依据」。NEUTRAL 也算不支持 ——
    这里的语义是「材料没写 = 没依据」，而 NEUTRAL 正是「材料没说」。
  * 上限 512 token（中文约 1 字 1 token），材料可能超，所以要分块并取各块的最大蕴含概率。
"""
from __future__ import annotations

import re

import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer


class ChineseNLI:
    def __init__(self, path: str, max_len: int = 512):
        self.tok = AutoTokenizer.from_pretrained(path)
        self.model = AutoModelForSequenceClassification.from_pretrained(path).eval()
        self.max_len = max_len
        id2label = self.model.config.id2label
        self.ent_id = next(k for k, v in id2label.items()
                           if str(v).upper().startswith("ENTAIL"))
        self.id2label = id2label

    # ---- 分块：把材料切成若干段，使「段 + 断言」不超长 ----
    def _segments(self, doc: str) -> list[str]:
        parts = re.split(r"(?<=[。！？；\n])", doc)
        return [p for p in (s.strip() for s in parts) if p]

    def _pack(self, doc: str, claim: str) -> list[str]:
        segs = self._segments(doc) or [doc]
        chunks, cur = [], ""
        for s in segs:
            trial = (cur + s) if cur else s
            if len(self.tok(trial, claim)["input_ids"]) <= self.max_len - 4 or not cur:
                cur = trial
            else:
                chunks.append(cur)
                cur = s
        if cur:
            chunks.append(cur)
        return chunks

    def score(self, doc: str, claim: str) -> dict:
        chunks = self._pack(doc, claim)
        best_ent, best_chunk, per = -1.0, "", []
        for ch in chunks:
            enc = self.tok(ch, claim, truncation=True, max_length=self.max_len,
                           return_tensors="pt")
            with torch.no_grad():
                logits = self.model(**enc).logits
            probs = torch.nn.functional.softmax(logits, dim=-1)[0]
            p_ent = probs[self.ent_id].item()
            per.append(p_ent)
            if p_ent > best_ent:
                best_ent, best_chunk = p_ent, ch
        # argmax 口径：只在「蕴含」是最可能类别时才算支持
        enc = self.tok(best_chunk, claim, truncation=True, max_length=self.max_len,
                       return_tensors="pt")
        with torch.no_grad():
            logits = self.model(**enc).logits
        pred = int(logits.argmax(dim=-1).item())
        return {
            "p_entail": best_ent,
            "argmax": pred,
            "argmax_label": str(self.id2label[pred]),
            "label": 1 if pred == self.ent_id else 0,
            "n_chunks": len(chunks),
        }
