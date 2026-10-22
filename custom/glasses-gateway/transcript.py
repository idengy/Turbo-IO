"""官方录音转写分享页 → Markdown(2026-10-22,r36)。
官方 App「分享转写」只给 `https://share.rayneo.cn/<短码>` 链接,页面是 SPA,数据走带签名的接口。这里不复刻签名,
而是用无头 Chrome 像普通访客一样打开页面、读渲染后的 DOM(`--dump-dom`),按元素类名取标题、日期、大纲、逐段转写。
parse / to_markdown / valid_url 是纯函数(单测用合成 DOM);render 起 Chrome 子进程。"""
from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
import threading
from html.parser import HTMLParser

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
_URL = re.compile(r"^https://share\.rayneo\.cn/([A-Za-z0-9]{4,32})/?$")
_LOCK = threading.Lock()   # 同时只跑一个 Chrome


def valid_url(url: str) -> bool:
    return bool(_URL.match((url or "").strip()))


class _Dom(HTMLParser):
    """只认分享页的几个类名;文字按所在元素归位。"""
    FIELDS = {"title-header__title": "title", "title-header__date": "date", "outline-item__time": "otime",
              "outline-item__content": "otext", "speaker-chip": "speaker", "slice-time": "stime", "slice-text": "text"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title = self.date = ""
        self.outline: list[list[str]] = []      # [time, text]
        self.slices: list[dict] = []            # {speaker, time, text}
        self.stack: list[str | None] = []       # 每层元素对应的字段名(无关元素为 None)
        self.buf: dict[str, str] = {}

    def handle_starttag(self, tag, attrs):
        classes = (dict(attrs).get("class") or "").split()
        field = next((self.FIELDS[c] for c in classes if c in self.FIELDS), None)
        if "outline-item" in classes:
            self.outline.append(["", ""])
        if "slice-item" in classes:
            self.slices.append({"speaker": "", "time": "", "text": ""})
        if tag in ("br", "img", "input", "meta", "link"):  # 空元素没有结束标签
            return
        self.stack.append(field)
        if field:
            self.buf[field] = ""

    def handle_endtag(self, tag):
        if not self.stack:
            return
        field = self.stack.pop()
        if not field:
            return
        text = re.sub(r"\s+", " ", self.buf.pop(field, "")).strip() if field != "text" else self.buf.pop(field, "").strip()
        if field == "title":
            self.title = self.title or text
        elif field == "date":
            self.date = self.date or text
        elif field in ("otime", "otext") and self.outline:
            self.outline[-1][0 if field == "otime" else 1] = text
        elif field in ("speaker", "stime", "text") and self.slices:
            self.slices[-1][{"speaker": "speaker", "stime": "time", "text": "text"}[field]] = text

    def handle_data(self, data):
        for f in self.stack:
            if f:
                self.buf[f] = self.buf.get(f, "") + data


def parse(dom: str) -> dict:
    p = _Dom()
    p.feed(dom)
    p.close()
    return {"title": p.title, "date": p.date,
            "outline": [o for o in p.outline if o[1]],
            "slices": [s for s in p.slices if s["text"]]}


def filename(title: str, date: str) -> str:
    """`2026-10-21 标题.md`;去掉文件名非法字符,标题空时用「录音转写」。"""
    m = re.match(r"(\d{4})[/-](\d{1,2})[/-](\d{1,2})", date or "")
    day = f"{m.group(1)}-{int(m.group(2)):02d}-{int(m.group(3)):02d} " if m else ""
    name = re.sub(r'[\\/:*?"<>|\r\n\t]+', " ", title or "").strip()[:60] or "录音转写"
    return f"{day}{name}.md"


def to_markdown(d: dict, url: str) -> tuple[str, str]:
    lines = [f"# {d['title'] or '录音转写'}", ""]
    if d["date"]:
        lines.append(f"- 录音时间:{d['date']}")
    lines += [f"- 来源:{url}", ""]
    if d["outline"]:
        lines += ["## 大纲", ""] + [f"- `{t}` {x}" if t else f"- {x}" for t, x in d["outline"]] + [""]
    lines += ["## 转写原文", ""]
    last = None
    for s in d["slices"]:
        head = f"`{s['time']}`" if s["time"] else ""
        if s["speaker"] and s["speaker"] != last:   # 同一说话人连续的段落只标一次说话人,时间戳每段都留
            head = f"**{s['speaker']}** {head}".strip()
        last = s["speaker"] or last
        if head:
            lines.append(head)
        lines += [s["text"], ""]
    return filename(d["title"], d["date"]), "\n".join(lines).rstrip() + "\n"


class RenderError(Exception):
    pass


def render(url: str, budget_ms: int = 15000, timeout: float = 60) -> str:
    """无头 Chrome 打开分享页,返回渲染后的 DOM。每次用临时 profile,用完删。"""
    profile = tempfile.mkdtemp(prefix="turboio-share-")
    try:
        r = subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                            f"--user-data-dir={profile}", "--lang=zh-CN", f"--virtual-time-budget={budget_ms}", "--dump-dom", url],
                           capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise RenderError(f"Chrome 渲染失败:{e.__class__.__name__}") from e
    finally:
        shutil.rmtree(profile, ignore_errors=True)
    if r.returncode != 0 or not r.stdout:
        raise RenderError(f"Chrome 渲染失败:exit {r.returncode}")
    return r.stdout.decode("utf-8", "replace")


def fetch(url: str) -> dict:
    """渲染 + 解析;第一次没有转写段落(长会议页渲染慢)就用 40 s 预算再试一次。"""
    with _LOCK:
        d = parse(render(url))
        if not d["slices"]:
            d = parse(render(url, budget_ms=40000, timeout=90))
    return d
