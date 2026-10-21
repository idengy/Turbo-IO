#!/usr/bin/env python3
"""眼镜问答网关(2026-10-19):挡在 Hermes API server 前面。
行情类问题不经 LLM,直接用 futures-kb 的天勤数据以 SSE 流式回答(首字 <1 s);其余请求原样转发 Hermes。
原因:Hermes 调工具时第一个可见字要 11 s,眼镜回答区早已关闭。纯标准库,密钥只在内存。"""
from __future__ import annotations  # 让 3.9 也能导入(类型标注里的 X | None)

import http.client
import json
import logging
import os
import re
import select
import socket
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
import watch  # noqa: E402  盯盘推流与排版(同目录)
import words  # noqa: E402  背单词排版与牌组(同目录)

HOST, PORT = "127.0.0.1", int(os.environ.get("GATEWAY_PORT", "8650"))
HERMES_HOST, HERMES_PORT = "127.0.0.1", int(os.environ.get("HERMES_PORT", "8642"))
KB_ROOT = Path(os.environ.get("FUTURES_KB_ROOT", "/Users/dyb/PROJECT/futures-kb"))
KB_PY = KB_ROOT / "venv/bin/python"
WORDS_DIR = Path(os.environ.get("WORDS_DIR", str(Path(__file__).resolve().parents[2] / "local/words")))
CACHE_TTL = 20.0
DEFAULT_TOP = 8
QUOTE_WORDS = ("行情", "多少钱", "现价", "报价", "价格", "多少点", "盘面", "自选", "报个价", "报一下")
ANALYSIS_WORDS = ("为什么", "为啥", "分析", "怎么看", "怎么样", "如何", "原因", "逻辑", "预测", "后市", "能不能", "要不要", "建议")

log = logging.getLogger("gateway")


def _api_key() -> str:
    env = Path.home() / ".hermes/.env"
    for line in env.read_text().splitlines():
        if line.startswith("API_SERVER_KEY="):
            return line.split("=", 1)[1].strip()
    return ""


API_KEY = _api_key()


class Quotes:
    """futures-kb `kb quotes --json` 的缓存;品种名表从 kb 包读一次。"""

    def __init__(self):
        self.lock = threading.Lock()
        self.data: dict | None = None
        self.ts = 0.0
        out = subprocess.run([str(KB_PY), "-c", "import json;from kb.varieties import VARIETIES;from kb.quotes import ALIASES;"
                              "print(json.dumps({'names':list(VARIETIES),'aliases':ALIASES},ensure_ascii=False))"],
                             cwd=KB_ROOT, capture_output=True, text=True, timeout=30)
        meta = json.loads(out.stdout)
        self.names: list[str] = meta["names"]
        self.aliases: dict = meta["aliases"]
        self.keys = sorted(set(self.names) | set(self.aliases), key=len, reverse=True)

    def find_names(self, text: str) -> list[str]:
        found: list[str] = []
        rest = text
        for k in self.keys:
            if k in rest:
                name = self.aliases.get(k, k)
                if name not in self.names:  # 别名表指向不存在的标准名时按原词算(kb 侧 2026-10-20 已修,这里兜底)
                    name = k
                if name in self.names and name not in found:
                    found.append(name)
                rest = rest.replace(k, " ")
        return found

    def get(self) -> dict:
        with self.lock:
            if self.data is None or time.time() - self.ts > CACHE_TTL:
                t0 = time.time()
                out = subprocess.run([str(KB_PY), "-m", "kb", "quotes", "--json"], cwd=KB_ROOT,
                                     capture_output=True, text=True, timeout=45)
                if out.returncode != 0:
                    raise RuntimeError((out.stderr or "").strip().splitlines()[-1:] or ["kb quotes failed"])
                self.data = json.loads(out.stdout)
                self.ts = time.time()
                log.info("quotes refreshed in %.1fs (%d rows, asof %s)", self.ts - t0, len(self.data["rows"]), self.data["asof"])
            return self.data

    @staticmethod
    def _price(v: float) -> str:
        if v >= 100:
            return f"{v:.0f}" if v == int(v) else f"{v:.1f}"
        return f"{v:g}"

    def answer(self, text: str) -> str:
        data = self.get()
        names = self.find_names(text)
        rows = [r for r in data["rows"] if r["last"] is not None]
        if names:
            rows = [r for r in rows if r["name"] in names]
            head = f"行情 {data['asof'][5:]}"
        else:
            rows = sorted(rows, key=lambda r: -abs(r["chg_pct"] or 0))[:DEFAULT_TOP]
            head = f"自选涨跌前{len(rows)} {data['asof'][5:]}"
        lines = [head]
        for r in rows:
            chg = f"{r['chg_pct']:+.2f}%" if r["chg_pct"] is not None else "-"
            lines.append(f"{r['name']} {self._price(r['last'])} {chg}")
        if names and not rows:
            lines.append("没有这些品种的成交")
        return "\n".join(lines)


def is_quote_question(text: str, quotes: "Quotes") -> bool:
    t = text.strip()
    if any(w in t for w in ANALYSIS_WORDS):
        return False
    if any(w in t for w in QUOTE_WORDS):
        return True
    # 只说了品种名(「螺纹钢」「铁矿和焦煤」)也当报价
    return bool(quotes.find_names(t)) and len(t) <= 16


class WordsControl:
    """一条背单词流的控制口:/v1/words/back 累加回看次数并唤醒流的等待。"""
    lock = threading.Lock()
    live: set = set()

    def __init__(self):
        self.event = threading.Event()
        self.backs = 0

    def back(self):
        with WordsControl.lock:
            self.backs += 1
        self.event.set()

    def take(self) -> int:
        with WordsControl.lock:
            n, self.backs = self.backs, 0
            self.event.clear()
        return n


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    quotes: Quotes = None  # set in main
    hub: "watch.StreamHub" = None  # set in main

    def log_message(self, fmt, *args):  # 只记路径与状态,不记 body
        log.info("%s %s", getattr(self, "command", "-"), getattr(self, "path", "-"))  # 请求行解析失败(如 URL 里裸 UTF-8)时还没有 path

    def _authorized(self) -> bool:
        return bool(API_KEY) and self.headers.get("Authorization", "") == f"Bearer {API_KEY}"

    def do_GET(self):
        url = urlsplit(self.path)
        path = url.path.rstrip("/")
        if path.endswith("/v1/words/status") or path.endswith("/v1/words/stream") or path.endswith("/v1/words/back"):
            if not self._authorized():
                return self._json(401, {"error": {"message": "unauthorized"}})
            query = parse_qs(url.query)
            if path.endswith("/v1/words/status"):
                with self.deck_lock:
                    return self._json(200, self.deck.status())
            if path.endswith("/v1/words/back"):  # 眼镜转旋钮(插件转发):正在放的流回上一个词
                with WordsControl.lock:
                    live = list(WordsControl.live)
                for c in live:
                    c.back()
                log.info("words back streams=%d", len(live))
                return self._json(200, {"ok": True, "streams": len(live)})
            return self._words(words.clamp_seconds(query.get("seconds", [""])[0]), "shuffle" if query.get("order", [""])[0] == "shuffle" else "seq",
                               query.get("probe", ["0"])[0] == "1")
        if path.endswith("/v1/watch/names") or path.endswith("/v1/watch/stream") or path.endswith("/v1/watch/resolve"):
            if not self._authorized():
                return self._json(401, {"error": {"message": "unauthorized"}})
            if path.endswith("/v1/watch/names"):
                return self._json(200, {"names": [{"name": n, "short": watch.short(n)} for n in self.quotes.names]})
            # BaseHTTPRequestHandler 把请求行按 latin-1 解码,原样 UTF-8 的中文要转回;%XX 编码的由 parse_qs 处理
            query = parse_qs(url.query.encode("latin-1", "ignore").decode("utf-8", "replace"))
            if path.endswith("/v1/watch/resolve"):
                q = query.get("q", [""])[0].strip()
                hit, unknown = self._contracts([q]) if q else ([], [q])
                if unknown or not hit:
                    return self._json(404, {"error": {"message": "未识别: " + q}})
                h = hit[0]
                return self._json(200, {"label": h["label"], "display": watch.short(h["label"]), "symbol": h["symbol"], "name": h["name"], "yymm": h["yymm"]})
            raw = query.get("names", [""])[0]
            tokens = [x.strip() for x in raw.split(",") if x.strip()]
            if len(tokens) > watch.MAX_NAMES:
                return self._json(400, {"error": {"message": f"最多 {watch.MAX_NAMES} 个合约"}})
            hit, unknown = self._contracts(tokens)
            if unknown or not hit:
                return self._json(400, {"error": {"message": "未识别: " + "、".join(unknown) if unknown else "缺少 names"}})
            # r10:mode=scroll(默认,一帧全部行,眼镜滚轮翻)/ rotate(网关每 page_seconds 换一页,每页 page_size 行)
            mode = "rotate" if query.get("mode", [""])[0] == "rotate" else "scroll"
            page_seconds = watch.clamp_page_seconds(query.get("page_seconds", [""])[0])
            try:
                page_size = max(1, min(watch.MAX_NAMES, int(query.get("page_size", [""])[0])))
            except ValueError:
                page_size = watch.PAGE_SIZE
            return self._watch([h["label"] for h in hit], mode, page_seconds, page_size)
        self._forward(None)

    def _contracts(self, tokens: list[str]):
        """品种 / 品种+月份 → 标准 label(铁矿石2701);交给 futures-kb 的 resolve_contracts,规则只写一处。"""
        if not tokens:
            return [], []
        out = subprocess.run([str(KB_PY), "-c", "import json,sys;from kb.quotes import resolve_contracts;h,u=resolve_contracts(json.loads(sys.argv[1]));print(json.dumps({'hit':h,'unknown':u},ensure_ascii=False))",
                              json.dumps(tokens, ensure_ascii=False)], cwd=KB_ROOT, capture_output=True, text=True, timeout=30)
        if out.returncode != 0:
            raise RuntimeError("resolve failed")
        r = json.loads(out.stdout)
        return r["hit"], r["unknown"]

    def _json(self, status: int, obj: dict):
        payload = json.dumps(obj, ensure_ascii=False).encode()
        if status >= 400:  # 2026-10-21:鉴权失败等原来不留痕,手机端「连接中」时查不到原因
            log.warning("%s %s -> %d (auth header %s)", self.command, urlsplit(self.path).path, status, "present" if self.headers.get("Authorization") else "missing")
        self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(payload)))
        self.end_headers(); self.wfile.write(payload)

    def _watch(self, names: list[str], mode: str = "scroll", page_seconds: float = watch.PAGE_SECONDS, page_size: int = watch.PAGE_SIZE):
        """SSE:≥1 s 一帧、永远是最新快照;无变化时每 ≤10 s 发 `: hb`;行情源出错发 error 事件后关闭。
        rotate 模式下翻页本身也算变化:到点换页即使行情没动也发一帧。"""
        stream = self.hub.acquire(names)
        total_pages = len(watch.pages(names, page_size))
        log.info("watch start names=%s clients=%d mode=%s pages=%d/%ss", ",".join(names), stream.clients, mode, total_pages, page_seconds)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream"); self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
        started = time.time()
        sent_version, last_frame, last_write = 0, 0.0, started  # 首帧等真实快照,最多等 3 s 再发占位
        sent_page = -1
        try:
            while True:
                with stream.cond:
                    if stream.version == sent_version and stream.error is None:
                        stream.cond.wait(timeout=1.0)
                    version, rows, error = stream.version, dict(stream.latest), stream.error
                now = time.time()
                if error is not None:
                    self._write_chunk(("data: " + json.dumps({"type": "error", "message": error}, ensure_ascii=False) + "\n\n").encode())
                    break
                page = watch.page_of(now - started, page_seconds, total_pages) if mode == "rotate" else 0
                changed = version != sent_version or page != sent_page or (sent_version == 0 and now - started >= 3.0)
                if changed and now - last_frame >= watch.FRAME_INTERVAL:
                    asof = watch.asof_of(rows)
                    text = watch.format_page(rows, names, page, page_size, asof=asof) if mode == "rotate" else watch.format_panel(rows, names, asof)
                    event = {"type": "quote", "text": text, "asof": asof, "page": page + 1, "pages": total_pages,
                             "rows": [{"name": n, "last": rows.get(n, {}).get("last"), "chg_pct": rows.get(n, {}).get("chg_pct")} for n in names]}
                    self._write_chunk(("data: " + json.dumps(event, ensure_ascii=False) + "\n\n").encode())
                    sent_version, sent_page, last_frame, last_write = max(version, 1), page, now, now
                elif now - last_write >= watch.HEARTBEAT:
                    self._write_chunk(b": hb\n\n"); last_write = now
            self.wfile.write(b"0\r\n\r\n"); self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            log.info("watch client went away")
        finally:
            self.close_connection = True  # 同 _words:流结束就关,不再 keep-alive 读下一请求(客户端已走,只会刷 reset 栈)
            self.hub.release(stream)
            log.info("watch end names=%s", ",".join(names))

    def _words(self, seconds: int, order: str, probe: bool):
        """背单词 SSE:每 `seconds` 一帧;一个词多帧时逐帧发完再取下一个词;probe=1 循环发标尺帧不动游标;
        词库空发 error 后关;两帧间隔 >HEARTBEAT 时补 `: hb` 心跳。
        2026-10-21:开始时先重放上次最后显示的词;游标在词的第一帧送出后才提交,等待期间每秒查客户端是否已断,
        断了就不再取词——手机停止不再白白吃掉一个没上镜的词。"""
        log.info("words start seconds=%d order=%s probe=%s", seconds, order, probe)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream"); self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
        pending: list[tuple[str, int, int]] = []  # (text, index, total)
        commit: dict | None = None  # 当前词的游标改动,第一帧送出后提交
        replay = not probe
        back_steps = 0  # >0 = 正在回看第几个之前的词;停手 BACK_PAUSE 秒后归 0,先重放转旋钮时正在显示的那个词再往后放
        rulers = words.ruler_frames()
        ctl = WordsControl()
        with WordsControl.lock:
            WordsControl.live.add(ctl)
        try:
            while True:
                backs = 0 if probe else ctl.take()
                if backs:
                    with self.deck_lock:
                        got = None
                        while backs and got is None:  # 历史不够就退到最早那个
                            got = self.deck.back_entry(back_steps + backs)
                            if got is None:
                                backs -= 1
                        if got is None and back_steps:  # 已退到最早那个:原地重放并重新计时
                            got = self.deck.back_entry(back_steps)
                    if got is not None:
                        back_steps += backs
                        entry, index, total = got
                        pending, commit = [(fr, index, total) for fr in words.layout(entry)], None
                        log.info("words back %d -> %s", back_steps, entry.get("word"))
                if not pending:
                    if probe:
                        pending = [(fr, i + 1, len(rulers)) for i, fr in enumerate(rulers)]
                    else:
                        if back_steps:  # 回看结束:回看期间不提交游标,current_id 仍是转旋钮时正在显示的词,重放它(2026-10-21 Bill 定)
                            replay = True
                        back_steps = 0
                        try:
                            with self.deck_lock:
                                got = self.deck.resume() if replay else None
                                if got is None:
                                    entry, index, total, commit = self.deck.peek(order)
                                else:
                                    entry, index, total = got
                                    log.info("words replay %s", entry.get("word"))
                        except LookupError as e:
                            self._write_chunk(("data: " + json.dumps({"type": "error", "message": str(e)}, ensure_ascii=False) + "\n\n").encode())
                            break
                        replay = False
                        pending = [(fr, index, total) for fr in words.layout(entry)]
                text, index, total = pending.pop(0)
                if self._peer_gone():
                    raise ConnectionResetError
                self._write_chunk(("data: " + json.dumps({"type": "panel", "text": text, "index": index, "total": total}, ensure_ascii=False) + "\n\n").encode())
                if commit is not None:
                    with self.deck_lock:
                        self.deck.commit(commit)
                    commit = None
                # 回看的词最后一帧停 BACK_PAUSE 秒;等待中收到回看立即处理
                hold = words.BACK_PAUSE if back_steps and not pending else seconds
                waited = since_hb = 0.0
                while waited < hold:
                    step = min(1.0, hold - waited)
                    if ctl.event.wait(step):
                        break
                    waited += step; since_hb += step
                    if self._peer_gone():
                        raise ConnectionResetError
                    if since_hb >= words.HEARTBEAT and waited < hold:
                        self._write_chunk(b": hb\n\n"); since_hb = 0.0
            self.wfile.write(b"0\r\n\r\n"); self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            log.info("words client went away")
        finally:
            with WordsControl.lock:
                WordsControl.live.discard(ctl)
        self.close_connection = True  # 流结束就关,不再按 HTTP/1.1 keep-alive 去读下一请求(客户端早走了,只会刷 reset 栈)
        log.info("words end")

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        if self.path.rstrip("/").endswith("/v1/chat/completions") and self._authorized():
            try:
                req = json.loads(body or b"{}")
                user = [m for m in req.get("messages", []) if m.get("role") == "user"]
                text = user[-1].get("content", "") if user else ""
                if isinstance(text, list):
                    text = " ".join(p.get("text", "") for p in text if isinstance(p, dict))
            except Exception:
                req, text = {}, ""
            if text and is_quote_question(text, self.quotes):
                try:
                    answer = self.quotes.answer(text)
                except Exception as e:
                    log.warning("quotes failed: %s", e)
                    answer = "行情暂时取不到,稍后再问"
                log.info("fast quote answer for %r (%d chars)", text[:30], len(answer))
                return self._sse(answer, req.get("model") or "hermes-agent", bool(req.get("stream", True)))
        self._forward(body)

    def _sse(self, answer: str, model: str, stream: bool):
        cid = "chatcmpl-gw-" + uuid.uuid4().hex[:12]
        created = int(time.time())
        if not stream:
            payload = json.dumps({"id": cid, "object": "chat.completion", "created": created, "model": model,
                                  "choices": [{"index": 0, "message": {"role": "assistant", "content": answer}, "finish_reason": "stop"}]},
                                 ensure_ascii=False).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(payload)))
            self.end_headers(); self.wfile.write(payload); return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream"); self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked"); self.end_headers()

        def chunk(delta: dict, finish=None):
            j = {"id": cid, "object": "chat.completion.chunk", "created": created, "model": model,
                 "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            self._write_chunk(("data: " + json.dumps(j, ensure_ascii=False) + "\n\n").encode())
        chunk({"role": "assistant"})
        for i, line in enumerate(answer.split("\n")):
            chunk({"content": ("" if i == 0 else "\n") + line})
        chunk({}, "stop")
        self._write_chunk(b"data: [DONE]\n\n")
        self.wfile.write(b"0\r\n\r\n"); self.wfile.flush()

    def _peer_gone(self) -> bool:
        """客户端是否已关连接(可读且 recv 0 字节 = 对端 FIN)。写入本身要等第二次写才报错,靠它发现不了刚断开的连接。"""
        try:
            readable, _, _ = select.select([self.connection], [], [], 0)
            return bool(readable) and self.connection.recv(1, socket.MSG_PEEK) == b""
        except OSError:
            return True

    def _write_chunk(self, data: bytes):
        self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n"); self.wfile.flush()

    def _forward(self, body: bytes | None):
        conn = http.client.HTTPConnection(HERMES_HOST, HERMES_PORT, timeout=120)
        headers = {k: v for k, v in self.headers.items() if k.lower() in ("authorization", "content-type", "accept", "user-agent")}
        if body is not None:
            headers["Content-Length"] = str(len(body))
        try:
            conn.request(self.command, self.path, body=body, headers=headers)
            resp = conn.getresponse()
        except Exception as e:
            log.warning("hermes unreachable: %s", e)
            payload = json.dumps({"error": {"message": f"Hermes 不可达:{e}", "type": "gateway_error"}}).encode()
            self.send_response(502); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(payload)))
            self.end_headers(); self.wfile.write(payload); return
        self.send_response(resp.status)
        ctype = resp.getheader("Content-Type", "application/json")
        self.send_header("Content-Type", ctype); self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
        try:
            while True:
                data = resp.read1(4096) if hasattr(resp, "read1") else resp.read(4096)
                if not data:
                    break
                self._write_chunk(data)
            self.wfile.write(b"0\r\n\r\n"); self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            log.info("client went away")
        finally:
            conn.close()


# Tailscale 保活(2026-10-21):手机走 5G 时隧道闲置断开后,手机端主动发起连不上 Mac(家里路由 IPv6 防火墙挡入站、
# Mac 的 DERP 落在纽伦堡,打洞协调失效),只有 Mac 先发包才能打通。每 KEEPALIVE_SECONDS 从 Mac ICMP ping 手机的 tailnet IP 一次。
# 不用 `tailscale ping`:LaunchAgent 里调 Tailscale.app 的 CLI 会去拉 GUI 失败,且退出码仍是 0。
KEEPALIVE_PEER = os.environ.get("TS_KEEPALIVE_PEER", "100.113.176.95")  # 手机 v2436a 的 tailnet IP;空串 = 关闭
KEEPALIVE_SECONDS = 25
KEEPALIVE_OFF = Path(__file__).resolve().parents[2] / "local/config/ts-keepalive-off"  # 文件存在 = 暂停保活(对照测试用),删掉即恢复,不用重启


def tailscale_keepalive():
    ok = None
    while True:
        if KEEPALIVE_OFF.exists():
            if ok != "paused":
                log.info("tailscale keepalive paused (%s exists)", KEEPALIVE_OFF.name)
            ok = "paused"
            time.sleep(KEEPALIVE_SECONDS)
            continue
        try:
            r = subprocess.run(["/sbin/ping", "-c", "1", "-t", "5", KEEPALIVE_PEER], capture_output=True, text=True, timeout=15)
            now = r.returncode == 0
            detail = [l for l in r.stdout.splitlines() if "bytes from" in l or "packet loss" in l][:1]
        except (OSError, subprocess.SubprocessError) as e:
            now, detail = False, [type(e).__name__]
        if now != ok:  # 只在通断变化时记一行
            log.info("tailscale keepalive %s %s: %s", KEEPALIVE_PEER, "ok" if now else "FAIL", " ".join(detail)[:120])
            ok = now
        time.sleep(KEEPALIVE_SECONDS)


def main():
    logging.basicConfig(level=logging.INFO, stream=sys.stderr, format="%(asctime)s %(levelname)s %(message)s")
    if not API_KEY:
        log.warning("API_SERVER_KEY not found in ~/.hermes/.env; quote fast-path disabled, everything forwards")
    Handler.quotes = Quotes()
    Handler.hub = watch.StreamHub([str(KB_PY), "-m", "kb", "stream"], KB_ROOT, log)
    Handler.deck = words.Deck(WORDS_DIR); Handler.deck_lock = threading.Lock()
    log.info("word deck: %d entries from %s", Handler.deck.total(), WORDS_DIR)
    log.info("variety names loaded: %d (+%d aliases)", len(Handler.quotes.names), len(Handler.quotes.aliases))
    if KEEPALIVE_PEER:
        threading.Thread(target=tailscale_keepalive, name="ts-keepalive", daemon=True).start()
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    srv.daemon_threads = True
    log.info("listening on http://%s:%d -> hermes %s:%d", HOST, PORT, HERMES_HOST, HERMES_PORT)
    srv.serve_forever()


if __name__ == "__main__":
    main()
