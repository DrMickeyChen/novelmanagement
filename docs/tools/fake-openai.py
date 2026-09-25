#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
E1 用的【假 OpenAI 兼容端点】—— 让「接真实模型」这条链路在不花钱的前提下被真的跑一遍。

    python docs/tools/fake-openai.py            # 默认监听 127.0.0.1:8899

★ 它怎么接进系统：把管理端的 `ai_config.base_url` 改成 `http://127.0.0.1:8899/v1`。
  **后端一行代码都不用改，也不用重新打包** —— 和 M4 用 `ES_URIS` 指向本地 TCP 代理
  做「ES 挂掉」实验是同一个手法。见 docs/M4-摘出search.md 九。

★★ 为什么必须有它：真实调用是要花钱的，而「花钱」这件事一旦进入开发循环，
   所有回归测试都会退化成「跑一次看看」—— 那正是本项目一路在避免的东西。
   有了它，渲染/配额/中断/超时/429/坏 JSON/幂等/联动/摘要组装 全都能反复跑，成本 0。

★★★ 故障注入的开关是【模型名】(`ai_config.model`)，不是 URL 参数。
   理由：`model` 本来就是「这次调用用哪个模型」的字段，把它当作
   「这次调用用哪种上游行为」的选择器，**契约上多一个字节都不用加**
   （不用 query、不用 header、不用第二套配置），而且它和 base_url 一样
   是管理端页面上能改的东西 —— 于是「换个上游行为」和「换个上游地址」
   是同一种操作，不需要记两套。

   可用模型名见 FAULTS 表。

★ stdout 只输出 ASCII：Windows 控制台的码页是 ANSI，
  打中文会变成乱码或者直接抛 UnicodeEncodeError。响应体走的是
  `encode('utf-8')` 的字节，和控制台码页无关。

★ 它【不提交任何真实 API Key】，也【不参与构建】。录制下来的真实响应字节
  放在 `docs/tools/recorded/`（`model=fake-replay` 时回放它们），
  里面只有模型的输出，没有凭据。
"""

import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))

PORT = int(os.environ.get("FAKE_PORT", "8899"))
HOST = os.environ.get("FAKE_HOST", "127.0.0.1")

# 一次流式响应吐多少个「字」（码点）。★ 每个字一帧，所以它同时决定帧数。
CHARS = int(os.environ.get("FAKE_CHARS", "60"))
# 每帧之间停多久。★ 它必须 > 0，否则「逐块到达」和「中断」两件事都测不出来
# （所有帧会在同一个 TCP 段里到达，看起来像一次性返回）。
DELAY_MS = int(os.environ.get("FAKE_DELAY_MS", "40"))
# fake-slow 用的间隔，故意比 CHARS * DELAY_MS 还长
SLOW_DELAY_MS = int(os.environ.get("FAKE_SLOW_DELAY_MS", "1500"))
REPLAY_FILE = os.environ.get("FAKE_REPLAY", os.path.join(HERE, "recorded", "r1-stream.sse"))

# 假端点的固定产出。★ 用「假端点输出。」重复再截断到 CHARS 个码点 ——
# 它是可预期的（测试要断言拼接结果），但又不是单字重复（能看出帧边界）。
BASE_TEXT = "假端点输出。"

# ★ 固定的 token 数，方便断言「不是 0」。真实调用里这三个数由模型给，
#   而在这里给固定值是为了让「usage 有没有被读到」变成一个确定的答案。
FAKE_USAGE = {"prompt_tokens": 11, "completion_tokens": 22, "total_tokens": 33}

# ★ 只有带这个前缀的 Authorization 才被接受。它验的是「Key 有没有被送对」，
#   而不是「Key 是不是真的」—— 后者只有真端点做得到（R3）。
KEY_PREFIX = "Bearer sk-"

_lock = threading.Lock()
STATS = {
    "requests": 0,
    "auth_failures": 0,
    "streams": 0,
    "disconnects": 0,
    "last": None,
    "log": [],
}


def _log(line):
    """★ ASCII only —— 见文件头的说明。"""
    print(line, flush=True)
    with _lock:
        STATS["log"].append(line)
        if len(STATS["log"]) > 200:
            del STATS["log"][:-200]


def _fault_of(model):
    """
    从模型名里取故障名。`deepseek-chat` / 任何不认识的模型名 → `ok`。

    ★ 默认落到 `ok` 而不是报错：管理端里 `model` 是会被真的填成
      `deepseek-chat` 的，那时这个假端点必须表现得像个正常端点。

    ---- FAULTS 表（`ai_config.model` 填 `fake-<名字>`）----
      ok               正常流式正文（非流式时是一段普通文本，解析器读不懂 → 那是故意可用的失败样本）
      no-done          不发 [DONE]
      no-usage         不发 usage 帧
      error-frame      流里发一个 error 对象
      truncate         吐 5 帧后掐断连接
      garbage          非流式：一段「我不能这样做」→ 解析失败（S8 阳性对照 ②）
      empty            真的空产出（用来证明它能和「失败」分开）
      slow             每帧 1.5 秒
      429 / 500 / 401  HTTP 错误
      replay           回放录制的真实响应字节
      verdict          非流式：一份【不带围栏】的合法审核 JSON
      json-fence       非流式：同上，但裹 ```json 围栏

    ★★ verdict 与 json-fence 的产出【只差那一对反引号】—— 这是刻意的：
      「解析器会不会剥围栏」因此是一次【差分】判断（铁律 8：和对照组比，
      别数个数）。两者跑出来必须是同一个判定，差异只可能来自围栏。
    """
    if not model or not model.startswith("fake-"):
        return "ok"
    return model[len("fake-"):]


# 审核场景的合法答复。★ riskLevel 取 2（不是 0）是有用的：
# mock 恒定返回 0，所以只有真跑一次非流式解析，才能验证
# 「AI 层的等级真的参与了 max() 合并」—— 而这条不花钱。
AUDIT_VERDICT = {"riskLevel": 2, "hitWords": ["演示词"], "comment": "含演示风险内容"}


def _sse(obj):
    """一帧 SSE：`data: <json>\\n\\n`。★ 末尾两个换行是 SSE 的帧分隔符，少一个就没有帧。"""
    return ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8")


def _chunk_frame(model, delta, finish=None):
    return _sse({
        "id": "chatcmpl-fake",
        "object": "chat.completion.chunk",
        "created": int(time.time()),
        "model": model,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    })


def _usage_frame(model):
    """
    ★★★ 带 usage 的那一帧，`choices` 是【空数组】—— 这不是我编的形状。
    真实的 OpenAI / DeepSeek 就是这么发的，而它正是判断 4 那个
    「静默为 0」陷阱的来源：为了躲开 `choices[0]` 的越界而
    「跳过 choices 为空的帧」，就会把这一帧连同 usage 一起跳过 →
    库里 token 全是 0，且不报错。
    """
    return _sse({
        "id": "chatcmpl-fake",
        "object": "chat.completion.chunk",
        "created": int(time.time()),
        "model": model,
        "choices": [],
        "usage": dict(FAKE_USAGE),
    })


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "fake-openai/1.0"

    # ---- 日志：默认打 stderr 且带时间戳，我们只要一行 ASCII ----
    def log_message(self, fmt, *args):
        pass

    def log_error(self, fmt, *args):
        pass

    # ---- 工具 ----

    def _json(self, status, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def _begin_sse(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        # ★ 用 chunked 而不是「读到连接关闭为止」：真实端点用的是 chunked，
        #   而我们这一期【必须】证明 Java 的 BodyHandlers.ofLines() 能逐行读它。
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

    def _frame(self, payload):
        """
        写一个 chunked 帧并 flush。

        ★★ 返回值是「有没有写出去」—— 而**客户端断开就是在这里被发现的**：
          写到一条已经被对端关掉的连接上会抛 BrokenPipeError / ConnectionResetError。
          ★ 第一次写不一定抛（数据先进内核缓冲区），所以这个检查
          【天然滞后几帧】—— 这正是「光靠 send() 失败来停生成会白烧 token」的原因，
          也正是我们那个 aborted 标志位存在的理由。
        """
        try:
            self.wfile.write(b"%x\r\n" % len(payload) + payload + b"\r\n")
            self.wfile.flush()
            return True
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError) as exc:
            with _lock:
                STATS["disconnects"] += 1
                if STATS["last"] is not None:
                    STATS["last"]["disconnect_at_chunk"] = STATS["last"].get("sent_chunks", 0)
            _log("CLIENT-DISCONNECT after %s chunks (%s)"
                 % (STATS["last"].get("sent_chunks") if STATS["last"] else "?", type(exc).__name__))
            self.close_connection = True
            return False

    def _end_stream(self):
        """chunked 的终止帧（0 长度）。★ 少写它，客户端会认为响应被截断。"""
        try:
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except Exception:
            pass

    def _read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

    def _auth_ok(self):
        return (self.headers.get("Authorization") or "").startswith(KEY_PREFIX)

    # ---- 路由 ----

    def do_GET(self):
        if self.path.startswith("/__status"):
            with _lock:
                body = dict(STATS)
            body["log"] = list(body["log"])
            self._json(200, body)
            return
        if self.path.startswith("/__reset"):
            with _lock:
                STATS.update({"requests": 0, "auth_failures": 0, "streams": 0,
                              "disconnects": 0, "last": None, "log": []})
            self._json(200, {"ok": True})
            return
        self._json(404, {"error": {"message": "not found"}})

    def do_POST(self):
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self._json(404, {"error": {"message": "not found"}})
            return

        body = self._read_body()
        model = body.get("model") or "unknown"
        stream = bool(body.get("stream"))
        fault = _fault_of(model)
        prompt = ""
        for msg in (body.get("messages") or []):
            if msg.get("role") == "user":
                prompt = msg.get("content") or ""

        with _lock:
            STATS["requests"] += 1
            STATS["last"] = {
                "model": model, "fault": fault, "stream": stream,
                "auth_ok": self._auth_ok(),
                "prompt_chars": len(prompt),
                "prompt_has_system": any(m.get("role") == "system" for m in (body.get("messages") or [])),
                "include_usage": bool((body.get("stream_options") or {}).get("include_usage")),
                "max_tokens": body.get("max_tokens"),
                "at": time.time(),
                "sent_chunks": 0,
                "disconnect_at_chunk": None,
            }
        _log("REQ model=%s fault=%s stream=%s auth_ok=%s prompt_chars=%d"
             % (model, fault, stream, self._auth_ok(), len(prompt)))

        # ---- 鉴权：验的是「Key 有没有被送对」 ----
        if not self._auth_ok():
            with _lock:
                STATS["auth_failures"] += 1
            # ★ 刻意模仿真实端点的「回显一段 Key」——真实 DeepSeek 的 401 body 里
            #   真的带 Key 片段。我们要用它验证【后端绝不把上游 body 写进日志/错误出口】。
            leaked = (self.headers.get("Authorization") or "")[:14]
            self._json(401, {"error": {
                "message": "Authentication Fails, Your api key is: %s..." % leaked,
                "type": "authentication_error"}})
            return

        if fault == "429":
            self._json(429, {"error": {"message": "Rate limit reached", "type": "rate_limit_error"}})
            return
        if fault == "500":
            self._json(500, {"error": {"message": "upstream exploded", "type": "server_error"}})
            return
        if fault == "401":
            self._json(401, {"error": {"message": "Authentication Fails, key invalid", "type": "authentication_error"}})
            return
        if fault == "replay":
            self._replay()
            return
        if stream:
            self._stream(model, fault)
        else:
            self._non_stream(model, fault)

    # ---- 流式 ----

    def _stream(self, model, fault):
        with _lock:
            STATS["streams"] += 1
        self._begin_sse()

        if fault == "empty":
            # 「真的空产出」—— 用来证明它能和「失败」分开（见 docs/E1 的判定性判据）
            if not self._frame(_usage_frame(model)):
                return
            self._frame(_chunk_frame(model, {}, "stop"))
            if self._frame(b"data: [DONE]\n\n"):
                self._end_stream()
            return

        text = (BASE_TEXT * (CHARS // len(BASE_TEXT) + 2))[:CHARS]
        delay = SLOW_DELAY_MS if fault == "slow" else DELAY_MS
        emit_usage = fault != "no-usage"

        for ch in text:
            with _lock:
                STATS["last"]["sent_chunks"] += 1
                n = STATS["last"]["sent_chunks"]
            if not self._frame(_chunk_frame(model, {"content": ch})):
                return
            if fault == "truncate" and n >= 5:
                # ★ 直接掐断：不发结束帧、不发 [DONE]、不发 chunked 终止帧。
                #   客户端看到的是「响应体提前结束」——一个 IOException，不是正常的流末尾。
                _log("TRUNCATE after %d chunks" % n)
                self.close_connection = True
                return
            if fault == "garbage" and n == 3:
                if not self._frame(b"data: {this is not json}\n\n"):
                    return
            if delay:
                time.sleep(delay / 1000.0)

        if fault == "error-frame":
            # 上游在流里报错（OpenAI 兼容协议允许这种形状）
            if not self._frame(_sse({"error": {"message": "context length exceeded",
                                               "type": "invalid_request_error"}})):
                return
            self._end_stream()
            return

        # ★★★ usage 帧必须在 [DONE] 之前、在 delta 帧之后 —— 真实的 DeepSeek 就是这样。
        #   ★ 它同样是最容易被「跳过 choices 为空的帧」这个防御性写法吃掉的一帧。
        if emit_usage:
            if not self._frame(_usage_frame(model)):
                return
        if not self._frame(_chunk_frame(model, {}, "stop")):
            return
        if fault != "no-done":
            if not self._frame(b"data: [DONE]\n\n"):
                return
            _log("STREAM-COMPLETE chunks=%d" % STATS["last"]["sent_chunks"])
        else:
            _log("STREAM-NO-DONE chunks=%d" % STATS["last"]["sent_chunks"])
        self._end_stream()

    def _replay(self):
        """回放录制下来的真实响应字节（`model=fake-replay`）。"""
        if not os.path.exists(REPLAY_FILE):
            self._json(500, {"error": {"message": "no recording at %s" % REPLAY_FILE}})
            return
        with open(REPLAY_FILE, "rb") as fh:
            raw = fh.read()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            # ★ 分片写 + 小延时，让回放也是「逐块到达」的（一次性 write 会掩盖网关缓冲）
            step = max(1, len(raw) // 20)
            for i in range(0, len(raw), step):
                self.wfile.write(raw[i:i + step])
                self.wfile.flush()
                time.sleep(0.02)
        except Exception:
            with _lock:
                STATS["disconnects"] += 1
            _log("CLIENT-DISCONNECT during replay")
        self.close_connection = True
        _log("REPLAY bytes=%d file=%s" % (len(raw), os.path.basename(REPLAY_FILE)))

    # ---- 非流式 ----

    def _non_stream(self, model, fault):
        text = (BASE_TEXT * (CHARS // len(BASE_TEXT) + 2))[:CHARS]
        if fault in ("verdict", "json-fence"):
            # ★★★ 真实模型【极常】把 JSON 包在 ```json 围栏里，哪怕提示词说了「只输出 JSON」。
            #   不剥围栏的话，S8 的审核会稳定地解析失败，而失败的表现是
            #   「没有 ai_audit_result 行」，非常难归因。
            #   ★★ 所以这里造一对【只差围栏】的产出：verdict 裸 JSON、
            #   json-fence 同一份 JSON 加围栏。两个模型名各跑一次，
            #   判定必须逐字相同 —— 差异只可能来自那对反引号（差分判断）。
            body = json.dumps(AUDIT_VERDICT, ensure_ascii=False, indent=2)
            text = ("```json\n" + body + "\n```") if fault == "json-fence" else body
        if fault == "garbage":
            text = "抱歉，我不能这样做。"
        self._json(200, {
            "id": "chatcmpl-fake",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": text},
                         "finish_reason": "stop"}],
            "usage": dict(FAKE_USAGE),
        })
        _log("NONSTREAM-COMPLETE fault=%s chars=%d" % (fault, len(text)))


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    _log("fake-openai listening on http://%s:%d/v1  (chars=%d delay=%dms)"
         % (HOST, PORT, CHARS, DELAY_MS))
    # ★ 这一行要与 _fault_of 的 FAULTS 表逐字一致：它是「这个端点能注入哪些故障」的
    #   唯一一句自陈，漏一个就等于把一个能力藏起来了（S8 加 verdict 时就漏过一次）。
    _log("faults: ok no-done no-usage error-frame truncate garbage empty slow "
         "429 500 401 replay verdict json-fence")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
