#!/usr/bin/env python3
"""有道单词本同步(2026-10-20):把 Bill 的有道词典单词本拉成 `local/words/wordbook.json`,供网关 words.py 排版上镜。

两种输入:
  1) 网页版接口(默认):读 `local/config/youdao-cookie.txt`(Chrome 登录 dict.youdao.com/wordbook 后,DevTools → Network →
     任意 webapi/words 请求 → Request Headers 的 Cookie 整行值,一行存文件),分页 GET /wordbook/webapi/books、/words。
  2) `--xml 有道导出.xml`:有道 PC 版「单词本 → 导出」的 XML(<item><word><trans><phonetic>)。
原始响应落 `local/words/raw/<时间>.json` 方便核对字段;Cookie 失效(有 code!=0 或 total=0 且列表空)报错退出 2,**不覆盖**旧词库。
Cookie 不打印、不进日志。纯标准库。"""
from __future__ import annotations

import argparse
import json
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
PAGE = 100
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"


def _get(path: str, cookie: str, **params) -> dict:
    url = f"{BASE}/{path}" + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, headers={"Cookie": cookie, "User-Agent": UA, "Referer": "https://dict.youdao.com/wordbook/", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read().decode("utf-8", "replace")
    try:
        return json.loads(body)
    except ValueError:
        raise SystemExit(f"{path}: 返回的不是 JSON(可能被跳转到登录页),Cookie 失效?前 80 字: {body[:80]!r}")


def _pick(d: dict, *keys, default=None):
    for k in keys:
        if k in d and d[k] not in (None, ""):
            return d[k]
    return default


def split_trans(text: str) -> list[str]:
    """释义字符串 → 条目列表:按换行、分号拆;去空;保留词性前缀。"""
    parts = re.split(r"[\n;；]+", text or "")
    return [p.strip() for p in parts if p.strip()]


def normalize_web(items: list[dict], books: dict[str, str]) -> list[dict]:
    out = []
    for it in items:
        word = str(_pick(it, "itemName", "word", default="")).strip()
        if not word:
            continue
        book_id = str(_pick(it, "bookId", default=""))
        added = _pick(it, "modifiedTime", "createTime", "addTime", default=0)
        try:
            added = int(added)
            if added > 10**12:  # 毫秒 → 秒
                added //= 1000
        except (TypeError, ValueError):
            added = 0
        out.append({"id": str(_pick(it, "itemId", "id", default=word)), "word": word,
                    "phonetic": str(_pick(it, "phonetic", default="")).strip(),
                    "trans": split_trans(str(_pick(it, "trans", "translation", default=""))),
                    "book": str(_pick(it, "bookName", default=books.get(book_id, "默认"))), "added": added})
    return out


def fetch_web(cookie: str, raw_dir: Path) -> list[dict]:
    books_resp = _get("books", cookie)
    books = {}
    for b in books_resp.get("data") or []:
        if isinstance(b, dict):
            books[str(_pick(b, "bookId", "id", default=""))] = str(_pick(b, "bookName", "name", default="默认"))
    items: list[dict] = []
    offset, total = 0, None
    while True:
        resp = _get("words", cookie, limit=PAGE, offset=offset)
        if resp.get("code") not in (0, "0", None):
            raise SystemExit(f"words: code={resp.get('code')} msg={resp.get('msg')} —— Cookie 失效或接口变了")
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
    return normalize_web(items, books)


def parse_xml(path: Path) -> list[dict]:
    root = ET.parse(path).getroot()
    out = []
    for i, item in enumerate(root.iter("item")):
        word = (item.findtext("word") or "").strip()
        if not word:
            continue
        out.append({"id": f"xml-{i}-{word}", "word": word, "phonetic": (item.findtext("phonetic") or "").strip(),
                    "trans": split_trans(item.findtext("trans") or ""), "book": (item.findtext("tags") or "导出").strip() or "导出", "added": 0})
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
    a = ap.parse_args(argv)
    if a.xml:
        entries = parse_xml(a.xml)
        if not entries:
            print("XML 里没有 <item><word>,未写入", file=sys.stderr); return 2
    else:
        try:
            cookie = a.cookie.read_text(encoding="utf-8").strip().splitlines()[0].strip()
        except (FileNotFoundError, IndexError):
            print(f"缺少 Cookie 文件 {a.cookie}:Chrome 登录 https://dict.youdao.com/wordbook 后,DevTools → Network → 任意 webapi/words 请求 → "
                  "Request Headers → Cookie 整行值,存成一行", file=sys.stderr); return 2
        cookie = cookie[len("Cookie:"):].strip() if cookie.lower().startswith("cookie:") else cookie
        try:
            entries = fetch_web(cookie, a.out / "raw")
        except urllib.error.URLError as e:
            print(f"网络错误:{e.reason}", file=sys.stderr); return 1
    target = write_book(entries, a.out)
    books: dict[str, int] = {}
    for e in entries:
        books[e["book"]] = books.get(e["book"], 0) + 1
    no_phon = sum(1 for e in entries if not e["phonetic"])
    print(f"写入 {target}:{len(entries)} 词;单词本 {books};无音标 {no_phon} 词")
    return 0


if __name__ == "__main__":
    sys.exit(main())
