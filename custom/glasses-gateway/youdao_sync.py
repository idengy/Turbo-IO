#!/usr/bin/env python3
"""有道单词本同步(2026-10-20):把 Bill 的有道词典单词本拉成 `local/words/wordbook.json`,供网关 words.py 排版上镜。

两种输入:
  1) 网页版接口(默认):Chrome 登录 https://www.youdao.com/webwordbook/wordlist (2026-10-20 实测:旧址 dict.youdao.com/wordbook/ 是 404),
     DevTools → Network → 过滤 Fetch/XHR → 名字为 `list?limit=48&offset=0…` 的请求 → Request Headers 的 Cookie 整行值,一行存
     `local/config/youdao-cookie.txt`。脚本分页 GET https://dict.youdao.com/wordbook/webapi/v2/word/list(页面 bundle 0bb1cbf.js 里读到的真实接口,
     参数 limit/offset/sort=time|alpha/lanTo/lanFrom;未登录返回 code 401)。条目字段实测:itemId/word/lanFrom/lanTo/trans/usphone/ukphone/
     createTime(ISO 串);个别词音标为空,再用公开的 https://dict.youdao.com/jsonapi?q=<word>(免登录)补,缓存在 `local/words/phonetic-cache.json`。
  2) `--xml 有道导出.xml`:有道 PC 版「单词本 → 导出」的 XML(<item><word><trans><phonetic>)。
原始响应落 `local/words/raw/<时间>.json` 方便核对字段;Cookie 失效(401 / code!=0 / total=0 且列表空)报错退出(非 0),**不覆盖**旧词库。
Cookie 不打印、不进日志。纯标准库。"""
from __future__ import annotations

import argparse
import json
from datetime import datetime
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORDS_DIR = ROOT / "local/words"
COOKIE_FILE = ROOT / "local/config/youdao-cookie.txt"
BASE = "https://dict.youdao.com/wordbook/webapi"
JSONAPI = "https://dict.youdao.com/jsonapi"
WEB_PAGE = "https://www.youdao.com/webwordbook/wordlist"
PAGE = 100
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
PHONETIC_PAUSE = 0.15   # jsonapi 逐词查询间隔,182 词约 30 s


def _get(url: str, cookie: str = "", **params) -> dict:
    if params:
        url += "?" + urllib.parse.urlencode(params)
    headers = {"User-Agent": UA, "Referer": WEB_PAGE, "Origin": "https://www.youdao.com", "Accept": "application/json"}
    if cookie:
        headers["Cookie"] = cookie
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read().decode("utf-8", "replace")
    try:
        return json.loads(body)
    except ValueError:
        raise SystemExit(f"{url.split('?')[0]}: 返回的不是 JSON(可能被跳转到登录页),Cookie 失效?前 80 字: {body[:80]!r}")


def _pick(d: dict, *keys, default=None):
    for k in keys:
        if k in d and d[k] not in (None, ""):
            return d[k]
    return default


def split_trans(text: str) -> list[str]:
    """释义字符串 → 条目列表:按换行、分号拆;去空;保留词性前缀。"""
    parts = re.split(r"[\n;；]+", text or "")
    return [p.strip() for p in parts if p.strip()]


def _epoch(v) -> int:
    """createTime 可能是毫秒数、秒数或 ISO 字符串(v2 实测 "2026-10-20T07:57:50.820+0000")→ 秒;解析不了返回 0。"""
    if v in (None, ""):
        return 0
    try:
        n = int(v)
        return n // 1000 if n > 10**12 else n
    except (TypeError, ValueError):
        pass
    txt = str(v).strip()
    txt = re.sub(r"([+-]\d{2})(\d{2})$", r"\1:\2", txt).replace("Z", "+00:00")
    try:
        return int(datetime.fromisoformat(txt).timestamp())
    except ValueError:
        return 0


def normalize_web(items: list[dict], books: dict[str, str], prefer: str = "us") -> list[dict]:
    """v2/word/list 条目实测字段(2026-10-20):itemId/word/lanFrom/lanTo/trans/usphone/ukphone/createTime;旧接口字段名也兼容。"""
    phon_keys = ("usphone", "ukphone", "phonetic") if prefer != "uk" else ("ukphone", "usphone", "phonetic")
    out = []
    for it in items:
        word = str(_pick(it, "itemName", "word", default="")).strip()
        if not word:
            continue
        book_id = str(_pick(it, "bookId", default=""))
        added = _epoch(_pick(it, "modifiedTime", "createTime", "addTime", default=0))
        out.append({"id": str(_pick(it, "itemId", "id", default=word)), "word": word,
                    "phonetic": str(_pick(it, *phon_keys, default="")).strip(),
                    "trans": split_trans(str(_pick(it, "trans", "translation", default=""))),
                    "book": str(_pick(it, "bookName", default=books.get(book_id, "默认"))),
                    "lang": str(_pick(it, "lanFrom", default="en")), "added": added})
    return out


def fetch_web(cookie: str, raw_dir: Path, prefer: str = "us") -> list[dict]:
    try:
        books_resp = _get(f"{BASE}/books", cookie)
    except urllib.error.HTTPError:
        books_resp = {}
    books = {}
    for b in books_resp.get("data") or []:
        if isinstance(b, dict):
            books[str(_pick(b, "bookId", "id", default=""))] = str(_pick(b, "bookName", "name", default="默认"))
    items: list[dict] = []
    offset, total = 0, None
    while True:
        resp = _get(f"{BASE}/v2/word/list", cookie, limit=PAGE, offset=offset, sort="time", lanTo="", lanFrom="")
        if resp.get("code") in (401, "401"):
            raise SystemExit("接口返回 401 用户未登录:Cookie 过期或复制的不是 dict.youdao.com 那条请求(要选 Network 里名字为 list?limit=… 的请求)。旧词库未改动")
        if resp.get("code") not in (0, "0", None):
            raise SystemExit(f"word/list: code={resp.get('code')} msg={resp.get('msg')} —— Cookie 失效或接口变了;旧词库未改动")
        data = resp.get("data") or {}
        page = data.get("itemList") or []
        total = int(data.get("total") or 0) if total is None else total
        items.extend(p for p in page if isinstance(p, dict))
        offset += PAGE
        if not page or offset >= total:
            break
    raw_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    (raw_dir / f"{stamp}.json").write_text(json.dumps({"books": books_resp, "total": total, "items": items}, ensure_ascii=False, indent=1), encoding="utf-8")
    if total == 0 and not items:
        raise SystemExit("接口返回 0 条:要么单词本真的空,要么 Cookie 已过期/未登录。重新从 Chrome 复制 Cookie 后再试;旧词库未改动")
    return normalize_web(items, books, prefer)


# ---------- 音标 ----------
def lookup_phonetic(word: str, prefer: str = "us") -> str:
    """公开 jsonapi 的 ec.word[0].ukphone/usphone;查不到返回 ""。只对英文词有意义。"""
    dicts = json.dumps({"count": 1, "dicts": [["ec"]]})
    try:
        d = _get(JSONAPI, q=word, dicts=dicts)
    except (urllib.error.URLError, SystemExit, OSError):
        return ""
    w = ((d.get("ec") or {}).get("word") or [{}])[0]
    order = ("usphone", "ukphone", "phone") if prefer == "us" else ("ukphone", "usphone", "phone")
    return str(_pick(w, *order, default="")).strip()


def enrich_phonetics(entries: list[dict], cache_file: Path, prefer: str = "us", lookup=lookup_phonetic, pause: float = PHONETIC_PAUSE) -> int:
    """给没音标的英文词补音标;缓存按 (prefer, word) 存,下次同步只查新词。返回本次新查的词数。"""
    try:
        cache = json.loads(cache_file.read_text(encoding="utf-8"))
        if not isinstance(cache, dict):
            cache = {}
    except (FileNotFoundError, ValueError):
        cache = {}
    queried = 0
    for e in entries:
        if e.get("phonetic") or e.get("lang", "en") not in ("en", ""):
            continue
        key = f"{prefer}:{e['word'].lower()}"
        if key not in cache:
            cache[key] = lookup(e["word"], prefer)
            queried += 1
            if pause:
                time.sleep(pause)
        e["phonetic"] = cache[key]
    if queried:
        try:
            cache_file.parent.mkdir(parents=True, exist_ok=True)
            cache_file.write_text(json.dumps(cache, ensure_ascii=False, indent=0), encoding="utf-8")
        except OSError:
            pass
    return queried


def parse_xml(path: Path) -> list[dict]:
    root = ET.parse(path).getroot()
    out = []
    for i, item in enumerate(root.iter("item")):
        word = (item.findtext("word") or "").strip()
        if not word:
            continue
        out.append({"id": f"xml-{i}-{word}", "word": word, "phonetic": (item.findtext("phonetic") or "").strip(),
                    "trans": split_trans(item.findtext("trans") or ""), "book": (item.findtext("tags") or "导出").strip() or "导出",
                    "lang": "en", "added": 0})
    return out


def write_book(entries: list[dict], out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / "wordbook.json"
    tmp = target.with_suffix(".tmp")
    tmp.write_text(json.dumps({"synced": time.strftime("%Y-%m-%d %H:%M:%S"), "entries": entries}, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(target)
    return target


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--xml", type=Path, help="改读有道 PC 版导出的 XML")
    ap.add_argument("--cookie", type=Path, default=COOKIE_FILE, help=f"Cookie 文件(默认 {COOKIE_FILE.relative_to(ROOT)})")
    ap.add_argument("--out", type=Path, default=WORDS_DIR, help="输出目录(默认 local/words)")
    ap.add_argument("--phonetic", choices=("us", "uk", "none"), default="us", help="音标取美音还是英音(默认美音);缺的用 jsonapi 补,none 不补")
    a = ap.parse_args(argv)
    if a.xml:
        entries = parse_xml(a.xml)
        if not entries:
            print("XML 里没有 <item><word>,未写入", file=sys.stderr); return 2
    else:
        try:
            cookie = a.cookie.read_text(encoding="utf-8").strip().splitlines()[0].strip()
        except (FileNotFoundError, IndexError):
            print(f"缺少 Cookie 文件 {a.cookie}:Chrome 登录 {WEB_PAGE} 后,DevTools → Network → Fetch/XHR → 名字为 list?limit=… 的请求 → "
                  "Request Headers → Cookie 整行值,存成一行", file=sys.stderr); return 2
        cookie = cookie[len("Cookie:"):].strip() if cookie.lower().startswith("cookie:") else cookie
        try:
            entries = fetch_web(cookie, a.out / "raw", "uk" if a.phonetic == "uk" else "us")
        except urllib.error.HTTPError as e:
            print(f"HTTP {e.code}:{e.reason}(401/403 = Cookie 过期或不对);旧词库未改动", file=sys.stderr); return 2
        except urllib.error.URLError as e:
            print(f"网络错误:{e.reason}", file=sys.stderr); return 1
    queried = 0
    if a.phonetic != "none":
        queried = enrich_phonetics(entries, a.out / "phonetic-cache.json", a.phonetic)
    target = write_book(entries, a.out)
    books: dict[str, int] = {}
    for e in entries:
        books[e["book"]] = books.get(e["book"], 0) + 1
    no_phon = sum(1 for e in entries if not e["phonetic"])
    print(f"写入 {target}:{len(entries)} 词;单词本 {books};本次新查音标 {queried} 词;仍无音标 {no_phon} 词")
    return 0


if __name__ == "__main__":
    sys.exit(main())
