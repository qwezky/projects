#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""远程「免费模型」客户端：OpenAI 兼容的 chat/completions，带重试与降级。

设计要点（都是实测换来的，改动前先看 README 的「为什么这么写」）：

1. **优先 json_schema，不要 json_object。**
   实测 DeepSeek-V4-Pro：json_schema 3.4s 干净返回；json_object 会先写一大段
   reasoning，把 max_tokens 烧光，`content` 返回空字符串。
   凡是有思考链的模型（V4.1-Flash / GLM-4.7-Flash / Step-3.7-Flash / ZAI）都这样。
   所以判定「成功」的标准是 content 能解析出 JSON，reasoning 再长也不算数。

2. **必须有退避重试。** 免费档会 429（实测连续 4 次里第 3 次就被拒）。
   429 不是错误配置，是常态，退避后能恢复。

3. **必须有降级链。** 单模型日上限（ModelScope 500/天/模型）用完就换下一个。

4. **密钥不落盘到项目里。** 从 ~/.dsh/.credentials.yaml 的 refs 读，
   或从环境变量读；本模块只做只读访问，不打印密钥。

五个 provider 的端点与凭据名来自 ~/.dsh/profiles/web/cordis.patch.yml，
那边是 DSH 自己的路由配置，这里不改它。
"""

from __future__ import annotations

import json
import os
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

# provider -> (base_url, 凭据 ref 名)
PROVIDERS: dict[str, tuple[str, str]] = {
    "modelscope":  ("https://api-inference.modelscope.cn/v1", "MODELSCOPE_API_KEY"),
    "siliconflow": ("https://api.siliconflow.cn/v1",          "SILICONFLOW_API_KEY"),
    "zai":         ("https://open.bigmodel.cn/api/paas/v4",   "ZAI_API_KEY"),
    "openrouter":  ("https://openrouter.ai/api/v1",           "OPENROUTER_API_KEY"),
}

# 降级链：前一个不可用（429 用尽 / 空 content / 超时）就换下一个。
# 只放实测能稳定吐 JSON 的；ZAI glm-4.5-flash 与 Step-3.7-Flash 实测
# json_schema 下 content 为空（全在 reasoning 里），故不入链。
DEFAULT_CHAIN: list[tuple[str, str]] = [
    # 付费（扣魔粒）：只在余额够时才用，由 choose_chain 决定排前面
    ("modelscope",  "deepseek-ai/DeepSeek-V4-Pro"),   # 2 魔粒，质量最好
    ("modelscope",  "Qwen/Qwen3.8-Flash-Next"),       # 1 魔粒
    # 以下全部 ¥0，按实测速度排序
    ("openrouter",  "nvidia/nemotron-3-ultra-550b-a55b:free"),  # 1.8s
    ("siliconflow", "Qwen/Qwen2.5-7B-Instruct"),      # 1.5s
    ("siliconflow", "THUDM/GLM-4-9B-0414"),           # 3.0s
    ("siliconflow", "THUDM/GLM-Z1-9B-0414"),          # 6.6s
    ("zai",         "glm-4.5-flash"),                 # 24-38s，慢但稳
]

CREDENTIALS_PATH = Path.home() / ".dsh" / ".credentials.yaml"

# 哪些 provider 要扣魔粒。实测 2026-10-18：ModelScope 的 API-Inference 按次扣账
# （ultra 档 2 魔粒/次、standard 档 1 魔粒/次），而 Z.AI 与 SiliconFlow 是 ¥0。
PAID_PROVIDERS = {"modelscope"}
MAGICUBE_BALANCE_URL = "https://modelscope.cn/openapi/v1/magicubes/balance"
# 余额低于这个数就当"没有魔粒"，免得跑到一半耗尽。
MIN_PAID_BALANCE = 4

# 这些状态码值得退避重试；4xx 里只有 429 值得
RETRY_STATUS = {408, 409, 425, 429, 500, 502, 503, 504}

# 429 里混着一类"不是限流、是没钱了"的响应。实测 ModelScope 余额耗尽时返回：
#   HTTP 429 {"error":{"message":"insufficient balance"}}
# 状态码和限流一模一样，但退避重试毫无意义 —— 只会让每篇文章白等 10 秒再换模型。
# 命中这些标记就把该模型标记为"已死"，本轮直接跳过。
BALANCE_MARKERS = ("insufficient balance", "no available balance",
                   "quota exceeded", "余额不足", "额度已用尽", "credit")


class CredentialError(RuntimeError):
    pass


def read_ref(name: str, path: Path | None = None) -> str:
    """读凭据 ref。优先级：环境变量(同名/大写) > credentials.yaml。

    只读，不缓存到磁盘，不打印。
    """
    env = os.environ.get(name) or os.environ.get(name.upper())
    if env:
        return env.strip()

    p = path or CREDENTIALS_PATH
    if not p.exists():
        raise CredentialError(f"找不到凭据文件 {p}，也没有环境变量 {name}")

    text = p.read_text(encoding="utf-8")
    # 先用 PyYAML（如果装了）；否则退回手写解析——这个文件是
    # refs:\n  KEY: value 的扁平结构，正则足够稳，且不引入依赖。
    try:
        import yaml  # type: ignore
        data = yaml.safe_load(text) or {}
        val = (data.get("refs") or {}).get(name)
        if val:
            return str(val).strip()
    except ImportError:
        pass
    except Exception:
        pass

    in_refs = False
    for line in text.splitlines():
        if re.match(r"^refs:\s*$", line):
            in_refs = True
            continue
        if in_refs:
            if re.match(r"^\S", line):      # 出了 refs 段
                break
            m = re.match(r"""^\s+([A-Za-z0-9_]+)\s*:\s*(.+?)\s*$""", line)
            if m and m.group(1) == name:
                return m.group(2).strip().strip("'\"")
    raise CredentialError(f"凭据文件里没有 ref {name}（也没有环境变量 {name}）")


def magicube_balance(timeout: int = 15) -> int | None:
    """查 ModelScope 魔粒余额。查不到返回 None（当作"不知道"，不阻断）。"""
    try:
        key = read_ref("MODELSCOPE_API_KEY")
        req = urllib.request.Request(
            MAGICUBE_BALANCE_URL, headers={"Authorization": f"Bearer {key}"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return int(json.load(r)["data"]["available_balance"])
    except Exception:  # noqa: BLE001
        return None


def choose_chain(chain: list[tuple[str, str]], prefer_paid: bool = True
                 ) -> tuple[list[tuple[str, str]], str]:
    """按魔粒余额决定用哪条链。

    「有魔粒就用 V4-Pro，没有就用 ¥0 的」—— 用户 2026-10-18 明确的要求。
    余额查不到时保守地走 ¥0 链：宁可慢，不要跑到一半被欠费中断。

    返回 (排好序的链, 一句给人看的原因)。
    """
    paid = [c for c in chain if c[0] in PAID_PROVIDERS]
    free = [c for c in chain if c[0] not in PAID_PROVIDERS]

    if not prefer_paid or not paid:
        return free + paid, "已配置为只用 ¥0 模型"
    if not free:
        return paid, "链里只有付费模型"

    bal = magicube_balance()
    if bal is None:
        return free + paid, "查不到魔粒余额 → 保守用 ¥0 模型"
    if bal >= MIN_PAID_BALANCE:
        return paid + free, f"魔粒余额 {bal} → 优先 {paid[0][0]}:{paid[0][1]}（约 {bal} 次）"
    return free + paid, f"魔粒余额 {bal} < {MIN_PAID_BALANCE} → 改用 ¥0 模型"


class RemoteLLM:
    """带重试与降级链的最小客户端。"""

    def __init__(self, chain: list[tuple[str, str]] | None = None,
                 timeout: int = 120,
                 min_interval: float = 1.0,
                 max_retries: int = 4,
                 verbose: bool = False):
        self.chain = chain or list(DEFAULT_CHAIN)
        self.timeout = timeout
        self.min_interval = min_interval
        self.max_retries = max_retries
        self.verbose = verbose
        self._keys: dict[str, str] = {}
        self._last_call = 0.0
        self.last_model = ""          # 上一次真正出结果的 "provider/model"
        self.dead: set[str] = set()   # 额度耗尽的模型，本轮不再尝试
        # 统计：每个模型成功/失败次数，方便报账
        self.stats: dict[str, dict[str, int]] = {}

    # ---------- 内部 ----------

    def _key(self, provider: str) -> str:
        if provider not in self._keys:
            if provider not in PROVIDERS:
                raise CredentialError(f"未知 provider: {provider}")
            self._keys[provider] = read_ref(PROVIDERS[provider][1])
        return self._keys[provider]

    def _pace(self) -> None:
        """免费档按账号算速率，串行且保持最小间隔。"""
        gap = self.min_interval - (time.time() - self._last_call)
        if gap > 0:
            time.sleep(gap)
        self._last_call = time.time()

    def _bump(self, key: str, field: str) -> None:
        self.stats.setdefault(key, {"ok": 0, "fail": 0,
                                    "429": 0, "empty": 0})[field] += 1

    def _post(self, provider: str, model: str, payload: dict) -> dict:
        base = PROVIDERS[provider][0]
        req = urllib.request.Request(
            base + "/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self._key(provider)}",
            },
        )
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            return json.load(r)

    @staticmethod
    def _extract_json(text: str) -> dict | None:
        t = re.sub(r"```(?:json)?|```", "", (text or "")).strip()
        if not t:
            return None
        try:
            obj = json.loads(t)
            return obj if isinstance(obj, dict) else None
        except json.JSONDecodeError:
            pass
        depth, start = 0, None
        for i, ch in enumerate(t):
            if ch == "{":
                if depth == 0:
                    start = i
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0 and start is not None:
                    try:
                        obj = json.loads(t[start:i + 1])
                        if isinstance(obj, dict):
                            return obj
                    except json.JSONDecodeError:
                        pass
                    start = None
        return None

    # ---------- 对外 ----------

    def _call_once(self, provider: str, model: str, payload: dict):
        """发一次请求。返回 (状态, message dict, 错误)。

        状态取值：
          ok      —— 拿到 200
          balance —— 额度/余额耗尽（429 里夹带的 "insufficient balance" 之类）
          retry   —— 值得退避重试（限流、5xx、超时）
          fail    —— 再试也没用（其它 4xx）
        """
        try:
            resp = self._post(provider, model, payload)
        except urllib.error.HTTPError as exc:
            body = ""
            try:
                body = exc.read()[:200].decode("utf-8", "replace")
            except Exception:  # noqa: BLE001
                pass
            low = body.lower()
            detail = f"HTTP {exc.code} {body[:100]}"
            if any(m in low for m in BALANCE_MARKERS):
                return "balance", {}, detail
            if exc.code in RETRY_STATUS:
                return "retry", {}, detail
            return "fail", {}, detail
        except Exception as exc:  # noqa: BLE001
            return "retry", {}, f"{type(exc).__name__}: {str(exc)[:90]}"
        msg = ((resp.get("choices") or [{}])[0].get("message")) or {}
        return "ok", msg, ""

    def chat_json(self, user: str, *, system: str = "", schema: dict | None = None,
                  temperature: float = 0.2, max_tokens: int = 800,
                  ) -> tuple[dict | None, str, str]:
        """返回 (JSON, 原始文本, 错误)。内部已做重试与降级。

        schema 给了就优先用 json_schema（最稳最快）；否则退 json_object。
        额度耗尽的模型会被记进 self.dead，本轮不再尝试 —— 否则每篇文章都要
        先在死模型上白等 10 秒重试才换下一个。
        """
        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": user})

        last_err = ""
        for provider, model in self.chain:
            tag = f"{provider}/{model}"
            if tag in self.dead:
                continue
            base = {"model": model, "messages": messages,
                    "temperature": temperature, "max_tokens": max_tokens}
            attempts = []
            if schema:
                attempts.append(("json_schema", {**base, "response_format": {
                    "type": "json_schema",
                    "json_schema": {"name": "result", "strict": True,
                                    "schema": schema}}}))
            attempts.append(("json_object",
                             {**base, "response_format": {"type": "json_object"}}))
            attempts.append(("无约束", dict(base)))

            aborted = False
            for label, payload in attempts:
                if aborted:
                    break
                for retry in range(self.max_retries):
                    self._pace()
                    status, msg, err = self._call_once(provider, model, payload)
                    if status == "balance":
                        self.dead.add(tag)
                        aborted = True
                        last_err = f"{tag} {label}: 额度耗尽 {err}"
                        if self.verbose:
                            print(f"    ⛔ {tag} 额度耗尽，本轮跳过")
                        break
                    if status == "retry" and retry < self.max_retries - 1:
                        wait = 2 ** retry * 1.5
                        if "429" in err:
                            self._bump(tag, "429")
                        if self.verbose:
                            print(f"    ↻ {tag} {label} {err[:50]}，{wait:.0f}s 后重试")
                        time.sleep(wait)
                        continue
                    if status != "ok":
                        last_err = f"{tag} {label}: {err}"
                        break
                    text = msg.get("content") or ""
                    data = self._extract_json(text)
                    if isinstance(data, dict):
                        self._bump(tag, "ok")
                        self.last_model = tag
                        return data, text, ""
                    # content 空 + 有 reasoning = 思考链吃光预算，换下一种 response_format
                    if not text.strip() and (msg.get("reasoning_content") or msg.get("reasoning")):
                        self._bump(tag, "empty")
                        last_err = f"{tag} {label}: 思考链吃光 max_tokens，content 为空"
                    else:
                        last_err = f"{tag} {label}: 输出不是 JSON: {text[:60]!r}"
                    break
            self._bump(tag, "fail")
            if self.verbose:
                print(f"    ✗ {tag} 放弃：{last_err[:90]}")
        return None, "", last_err

    def summary(self) -> str:
        """一行报账：哪些模型成功了几次。"""
        parts = []
        for tag, st in self.stats.items():
            if st["ok"] or st["fail"]:
                parts.append(f"{tag}: ok={st['ok']} fail={st['fail']}"
                             f"{' 429=' + str(st['429']) if st['429'] else ''}"
                             f"{' empty=' + str(st['empty']) if st['empty'] else ''}")
        return "; ".join(parts) if parts else "（无调用）"


if __name__ == "__main__":
    # 自检：跑一条真实请求，确认凭据与端点都通
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--prompt", default="用一句话说明什么是爬虫，"
                                        "只输出一个 JSON 对象 {\"answer\":\"...\"}")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()

    schema = {"type": "object", "properties": {"answer": {"type": "string"}},
              "required": ["answer"], "additionalProperties": False}
    llm = RemoteLLM(verbose=a.verbose)
    data, text, err = llm.chat_json(a.prompt, schema=schema, max_tokens=200)
    print("结果:", data)
    print("报账:", llm.summary())
    if err:
        print("错误:", err)
