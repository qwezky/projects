#!/usr/bin/env python3
"""MiniCheck 打分器 —— 官方 minicheck_web/inference.py 的忠实复现（CPU）。

与官方唯一的差别（已在运行时断言把关）：
  官方用 nltk.sent_tokenize 切句，其 punkt 数据需从 GitHub 下载（本机不可达）。
  官方随后按「500 个空白分隔的词」为一组分块（chunk_size=500），并对各块取 max。
  我们的文档都很短，空白分词后远小于 500 → **恒为单块**，此时「怎么切句」对
  最终输入不产生影响。所以这里用「按行切」替代，并在 score() 里断言
  chunk 数 == 1；一旦出现多块（文档变长）就直接报错，绝不悄悄给错结果。

官方打分方式（照抄）：
  text = "predict: " + doc + eos + claim
  只用 1 个 decoder 起始 token 前向，取 logits 中 label token 3 / 209 两列做 softmax，
  第 1 列即「supported」概率；多块取 max。
"""
from __future__ import annotations

import torch
import torch.nn as nn
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

LABEL_TOKEN_IDS = [3, 209]  # 官方硬编码注释：3 = no support, 209 = support


def _split_sents(block: str) -> list[str]:
    """替代 nltk.sent_tokenize 的按行切分（见模块 docstring）。"""
    return [block] if block else []


def sent_tokenize_with_newlines(text: str) -> list[str]:
    """官方同名函数的复现：按行切，每行后补一个 '\n' 标记。"""
    out: list[str] = []
    for block in text.split("\n"):
        out.extend(_split_sents(block))
        out.append("\n")
    return out[:-1]


class MiniCheckScorer:
    def __init__(self, path: str, max_input_length: int = 2048, batch_size: int = 8):
        self.device = "cpu"
        self.model = AutoModelForSeq2SeqLM.from_pretrained(path).to(self.device).eval()
        self.tokenizer = AutoTokenizer.from_pretrained(path)
        self.default_chunk_size = 500
        self.chunk_size = 500
        self.max_input_length = max_input_length
        self.max_output_length = 256
        self.batch_size = batch_size
        self.softmax = nn.Softmax(dim=-1)

    @staticmethod
    def _chunks(lst, n):
        for i in range(0, len(lst), n):
            yield lst[i:i + n]

    def _chunk_doc(self, doc: str) -> list[str]:
        sents = sent_tokenize_with_newlines(doc) or [""]
        cur, cur_n, out = [], 0, []
        for s in sents:
            n = len(s.split())
            if cur_n + n > self.chunk_size:
                out.append(" ".join(cur))
                cur, cur_n = [s], n
            else:
                cur.append(s)
                cur_n += n
        if cur:
            out.append(" ".join(cur))
        chunks = [c.replace(" \n ", "\n").strip() for c in out]
        return [c for c in chunks if c != ""]

    def _inference(self, docs: list[str], claims: list[str]) -> list[float]:
        """返回每个 (doc, claim) 的 supported 概率。"""
        texts = [self.tokenizer.eos_token.join([d, c]) for d, c in zip(docs, claims)]
        probs: list[float] = []
        for mini in self._chunks(texts, self.batch_size):
            enc = self.tokenizer(
                ["predict: " + t for t in mini],
                max_length=self.max_input_length,
                truncation=True,
                padding=True,
                return_tensors="pt",
            )
            with torch.no_grad():
                dec = torch.zeros((enc["input_ids"].size(0), 1), dtype=torch.long)
                logits = self.model(
                    input_ids=enc["input_ids"],
                    attention_mask=enc["attention_mask"],
                    decoder_input_ids=dec,
                ).logits.squeeze(1)
                label_logits = logits[:, torch.tensor(LABEL_TOKEN_IDS)].cpu()
                # 官方写法：torch.nn.functional.softmax(label_logits, dim=-1)
                probs.extend(torch.nn.functional.softmax(
                    label_logits, dim=-1)[:, 1].tolist())
        return probs

    def score(self, doc: str, claim: str) -> dict:
        chunks = self._chunk_doc(doc)
        assert len(chunks) == 1, (
            f"文档被切成 {len(chunks)} 块——此时「按行切句」不再是等价替代，"
            f"本脚本的结论不再可信（官方用 punkt 切句）。"
        )
        per_chunk = self._inference([chunks[0]], [claim])
        p = per_chunk[0]
        return {
            "prob_supported": p,
            "label": 1 if p > 0.5 else 0,
            "n_chunks": len(chunks),
            "used_chunk": chunks[0],
        }
