"""背单词(2026-10-20):有道单词本 → 眼镜业务 19 文字面板的排版与牌组。纯标准库,无 Android/网络依赖,可直接单测。

词库 `local/words/wordbook.json`(youdao_sync.py 产出):[{id, word, phonetic, trans:[…], book, added}]
游标 `local/words/state.json`:{"current_id": …, "position": n, "shuffle_remaining": [...]}
面板约束(P0 实测):字号 2 每行 19 个汉字格、最多 5 行、整屏替换、≤384 字。英文每格几个字符未量,先按 ASCII_PER_CELL=2,探针后改这里即可,不用重打 APK。"""
from __future__ import annotations

import json
import random
import time
import unicodedata
from pathlib import Path

WIDTH = 19            # 每行汉字格(2026-10-20 P0-2 字号 2 实测 ≥19)
ASCII_PER_CELL = 2    # 一个汉字格放几个半角字符;探针(ruler_frames)后按实际改
MAX_LINES = 5
MAX_CHARS = 384       # NavCore.clip 上限
MIN_SECONDS, MAX_SECONDS, DEFAULT_SECONDS = 3, 60, 6
HEARTBEAT = 10.0

# ---------- 音标字形 ----------
# 2026-10-20 Bill 实机:眼镜字体**没有任何 IPA 字形**(非英文字母的音标符号全是空白),所以音标一律转成 ASCII 近似写法。
# 替换表取「看得懂」优先:sh/zh/ng/th/dh 用英文拼法,元音沿用 ASCII-IPA 惯例(I=ɪ、U=ʊ、@=ə、A=ɑ、O=ɔ/ɒ、V=ʌ、3=ɜ、ae=æ),重音 ' / , ,长音 : 。
# 词库 182 词实际用到的符号(2026-10-20 统计)按频次排:ˈ ɪ ə ː ɑ æ ʊ ʃ ʒ ˌ ɡ ɜ ɔ ŋ ʌ θ ɒ;ð 暂未出现但常见。
IPA_SYMBOLS = "ˈˌːɪəɑæʊʃʒɡɜɔŋʌθɒð"
IPA_FALLBACK = {"ˈ": "'", "ˌ": ",", "ː": ":", "ɪ": "I", "ə": "@", "ɑ": "A", "æ": "ae", "ʊ": "U", "ʃ": "sh", "ʒ": "zh",
                "ɡ": "g", "ɜ": "3", "ɔ": "O", "ŋ": "ng", "ʌ": "V", "θ": "th", "ɒ": "O", "ð": "dh",
                "ɛ": "e", "ɹ": "r", "ɚ": "@r", "ɝ": "3r", "ɫ": "l", "ʔ": "'", "ˑ": ".", "ɐ": "a", "ø": "oe", "ɵ": "o"}
IPA_ASCII_ONLY = True   # True:音标里所有非 ASCII 字符都替换(表里没有的先按 Unicode 分解取基础字母,再不行丢弃)


def safe_ipa(phon: str) -> str:
    """把眼镜显示不了的音标符号换成 ASCII 近似写法。IPA_ASCII_ONLY 关掉则原样返回。"""
    if not IPA_ASCII_ONLY:
        return phon
    phon = phon.replace("tʃ", "ch").replace("dʒ", "j")   # 双字母先换成英文拼法:church=ch3:ch、judge=jVj
    out = []
    for ch in phon:
        if ord(ch) < 128:
            out.append(ch)
        elif ch in IPA_FALLBACK:
            out.append(IPA_FALLBACK[ch])
        else:
            base = "".join(c for c in unicodedata.normalize("NFKD", ch) if ord(c) < 128)
            out.append(base)  # 例如 é→e;分解不出 ASCII 的直接丢
    return "".join(out)
# ---------- 宽度 ----------
def cell(ch: str) -> float:
    """一个字符占几个汉字格:东亚宽/全角 = 1,其余(英文、音标、数字、标点)= 1/ASCII_PER_CELL。"""
    return 1.0 if unicodedata.east_asian_width(ch) in ("W", "F") else 1.0 / ASCII_PER_CELL


def cells(s: str) -> float:
    return sum(cell(c) for c in s)


def wrap(text: str, width: float = WIDTH) -> list[str]:
    """按格宽折行:英文在空格处断,汉字逐字断;单个超长英文词硬切。空文本 → []。"""
    out: list[str] = []
    for para in text.replace("\r", "").split("\n"):
        line, used = "", 0.0
        for tok in _tokens(para):
            w = cells(tok)
            if tok == " ":
                if line and used + w <= width:
                    line += tok; used += w
                continue
            if used + w <= width:
                line += tok; used += w; continue
            if line:
                out.append(line.rstrip()); line, used = "", 0.0
            while w > width:  # 超长 token 硬切
                cut, acc = 0, 0.0
                for i, ch in enumerate(tok):
                    if acc + cell(ch) > width:
                        break
                    acc += cell(ch); cut = i + 1
                out.append(tok[:cut]); tok = tok[cut:]; w = cells(tok)
            line, used = tok, w
        if line:
            out.append(line.rstrip())
    return out


def _tokens(s: str) -> list[str]:
    """英文按词(连同紧跟的标点)成 token,汉字每字一个 token,空格单独一个。"""
    toks: list[str] = []
    buf = ""
    for ch in s:
        if ch == " ":
            if buf:
                toks.append(buf); buf = ""
            toks.append(" ")
        elif cell(ch) == 1.0:
            if buf:
                toks.append(buf); buf = ""
            toks.append(ch)
        else:
            buf += ch
    if buf:
        toks.append(buf)
    return toks


# ---------- 排版 ----------
def layout(entry: dict, width: float = WIDTH, max_lines: int = MAX_LINES) -> list[str]:
    """一个单词 → 若干帧(每帧 ≤max_lines 行)。首帧第 1 行「单词 /音标/」(放不下则音标独占第 2 行),其余行放释义;
    释义放不完的拆到后续帧,后续帧第 1 行仍是单词方便对照。"""
    word = (entry.get("word") or "").strip()
    phon = safe_ipa((entry.get("phonetic") or "").strip().strip("/").strip())
    phon = f"/{phon}/" if phon else ""
    head = [word]
    if phon:
        if cells(word + "  " + phon) <= width:
            head = [word + "  " + phon]
        else:
            head = [word] + wrap(phon, width)
    body: list[str] = []
    for t in entry.get("trans") or []:
        body.extend(wrap(str(t).strip(), width))
    if not body:
        body = ["(无释义)"]
    frames: list[str] = []
    room = max_lines - len(head)
    if room < 1:  # 音标太长把首帧占满:首帧只放单词+音标,释义从第 2 帧起
        frames.append("\n".join(head[:max_lines])); room = 0
        rest = body
    else:
        frames.append("\n".join(head + body[:room])); rest = body[room:]
    per = max_lines - 1
    while rest:
        frames.append("\n".join([word] + rest[:per])); rest = rest[per:]
    return [f[:MAX_CHARS] for f in frames]


def ipa_probe_frame() -> str:
    """音标字形探针:每个符号前带编号,Bill 报「几号是方块/空白」。第 1 行编号 1–9,第 2 行 10–18,后面是整词对照。"""
    items = [f"{i + 1}{ch}" for i, ch in enumerate(IPA_SYMBOLS)]
    return "\n".join([" ".join(items[:9]), " ".join(items[9:]), "上两行应全是空白;省略号…应可见",
                      "/" + safe_ipa("ˌserənˈdɪpəti") + "/ /" + safe_ipa("ˈθɪŋkɪŋ") + "/", "/" + safe_ipa("ɡʊd") + "/ /" + safe_ipa("ɔːl") + "/ /" + safe_ipa("ðæt") + "/ /" + safe_ipa("ɜːθ") + "/"])


def ruler_frames() -> list[str]:
    """宽度探针:Bill 在眼镜上数每行能看到第几个字符,据此定 ASCII_PER_CELL 与 IPA 字形是否可用。第 1 帧是音标字形探针。"""
    digits = "".join(str(i % 10) for i in range(1, 51))           # 50 个数字,每 10 个一循环
    lower = "abcdefghijklmnopqrstuvwxyz" * 2                       # 52 个小写
    cjk = "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸子丑寅卯"        # 24 个汉字(P0 为 19 格)
    ipa = "/ˈsɛrənˌdɪpɪti/ /ʃɒθðŋʒæɜːɔːuːɑːəʊaɪ/"
    mixed = "serendipity  /ˌserənˈdɪpəti/"
    return [
        ipa_probe_frame(),
        "\n".join([digits, lower, cjk, ipa, mixed]),
        "\n".join(["ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWX", "n. 意外发现珍奇事物的本领；机缘巧合 serendipity", "1234567890" * 3, cjk, "探针帧 2 · 数到第几格看不见"]),
    ]


# ---------- 牌组 ----------
class Deck:
    """词库 + 游标。线程安全由调用方(gateway)用 lock 保证;文件 mtime 变了自动重载,游标尽量停在同一个单词上。"""

    def __init__(self, root: Path):
        self.root = Path(root)
        self.book = self.root / "wordbook.json"
        self.state_file = self.root / "state.json"
        self.entries: list[dict] = []
        self.mtime = -1.0
        self.updated = ""
        self.state = self._read_state()

    # 加载
    def load(self) -> None:
        try:
            m = self.book.stat().st_mtime
        except FileNotFoundError:
            self.entries, self.mtime, self.updated = [], -1.0, ""
            return
        if m == self.mtime:
            return
        raw = json.loads(self.book.read_text(encoding="utf-8"))
        items = raw["entries"] if isinstance(raw, dict) else raw
        entries = [e for e in items if (e.get("word") or "").strip()]
        entries.sort(key=lambda e: (-(e.get("added") or 0), e["word"].lower()))  # 最近加的先看
        self.entries, self.mtime = entries, m
        self.updated = time.strftime("%Y-%m-%d %H:%M", time.localtime(m))
        # 词库变了:游标跟着 current_id 走,找不到就回 0
        cur = self.state.get("current_id")
        pos = next((i for i, e in enumerate(entries) if e.get("id") == cur), None)
        self.state["position"] = (pos + 1) % len(entries) if pos is not None and entries else 0  # current 已看过,游标指向它后面
        ids = {e.get("id") for e in entries}
        self.state["shuffle_remaining"] = [i for i in self.state.get("shuffle_remaining", []) if i in ids]

    def total(self) -> int:
        self.load(); return len(self.entries)

    def status(self) -> dict:
        self.load()
        books: dict[str, int] = {}
        for e in self.entries:
            b = e.get("book") or "默认"
            books[b] = books.get(b, 0) + 1
        return {"total": len(self.entries), "position": self.state.get("position", 0), "current_id": self.state.get("current_id"),
                "book_counts": books, "updated": self.updated, "width": WIDTH, "ascii_per_cell": ASCII_PER_CELL}

    # 取词
    def next(self, order: str = "seq") -> tuple[dict, int, int]:
        """返回 (词条, 该词在顺序表中的序号(1 起), 总数) 并把游标推到它后面。空词库抛 LookupError。"""
        self.load()
        n = len(self.entries)
        if n == 0:
            raise LookupError("单词本为空,先跑 youdao_sync.py")
        if order == "shuffle":
            remaining = [i for i in self.state.get("shuffle_remaining", [])]
            if not remaining:
                remaining = [e.get("id") for e in self.entries]
                random.shuffle(remaining)
            chosen = remaining.pop()
            idx = next((i for i, e in enumerate(self.entries) if e.get("id") == chosen), 0)
            self.state["shuffle_remaining"] = remaining
        else:
            idx = self.state.get("position", 0) % n
            self.state["position"] = (idx + 1) % n
        entry = self.entries[idx]
        self.state["current_id"] = entry.get("id")
        self._write_state()
        return entry, idx + 1, n

    # 游标文件
    def _read_state(self) -> dict:
        try:
            s = json.loads(self.state_file.read_text(encoding="utf-8"))
            return s if isinstance(s, dict) else {}
        except (FileNotFoundError, ValueError):
            return {}

    def _write_state(self) -> None:
        try:
            self.root.mkdir(parents=True, exist_ok=True)
            tmp = self.state_file.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.state, ensure_ascii=False), encoding="utf-8")
            tmp.replace(self.state_file)
        except OSError:
            pass  # 游标写不进去只影响「接着上次看」,不影响显示


def clamp_seconds(v) -> int:
    try:
        s = int(float(v))
    except (TypeError, ValueError):
        return DEFAULT_SECONDS
    return max(MIN_SECONDS, min(MAX_SECONDS, s))
