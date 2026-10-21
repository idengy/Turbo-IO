"""盯盘推流(2026-10-20):给眼镜业务 19 文字面板排版 5 行行情,并管理 futures-kb `kb stream` 子进程。
纯标准库、无导入副作用(可单测)。被 gateway.py 的 /v1/watch/* 路由使用。
- format_panel:每行「短名 价格 涨跌」;≤4 个品种时首行加 HH:MM:SS(5 个品种时不占位;非交易时段价格不动,
  没有时钟就看不出流是否活着);缺行打「短名 -- --」。WIDTH/COMPACT 由 P0 实机探针定。
- 超过一屏(2026-10-20 r10,上限 MAX_NAMES=12):mode=scroll 一帧发全部行,翻页靠眼镜滚轮(固件 aisubtitle
  ScrollTextWidget 的历史翻看,实测每次更新跳回最后一页,只能停 3 s);mode=rotate 由网关每 page_seconds 换一页(format_page,首行末尾标「1/3」),r12 起插件默认 rotate。
- Stream:一个 `kb stream 品种…` 子进程;读线程把 JSON 行并进 latest,version+1 并唤醒等待者;最后一个客户端
  走后 30 s 回收(StreamHub);exit 1 退避 2/4/8…≤30 s 重启,exit 2 置 error 唤醒客户端。"""
from __future__ import annotations

import json
import subprocess
import threading
import time

# 短名:只收 3 字以上的标准名;2 字名原样。单测断言 65 个品种无碰撞。
SHORT = {
    "螺纹钢": "螺纹", "铁矿石": "铁矿", "热卷": "热卷", "不锈钢": "不锈", "氧化铝": "氧铝",
    "棕榈油": "棕油", "玉米淀粉": "淀粉", "鸡蛋": "鸡蛋", "生猪": "生猪",
    "碳酸锂": "锂", "工业硅": "工硅", "多晶硅": "多硅",
    "燃料油": "燃油", "低硫燃料油": "低硫", "石油沥青": "沥青", "丁二烯橡胶": "丁二烯", "20号胶": "20胶",
    "集运欧线": "集运", "国际铜": "国铜", "原油": "原油",
    "聚丙烯": "PP", "乙二醇": "EG", "苯乙烯": "EB", "对二甲苯": "PX", "瓶片": "瓶片",
    "液化石油气": "LPG", "纯碱": "纯碱", "玻璃": "玻璃", "烧碱": "烧碱", "短纤": "短纤",
    "菜籽油": "菜油", "菜籽粕": "菜粕", "花生": "花生", "红枣": "红枣", "苹果": "苹果",
    "棉纱": "棉纱", "硅铁": "硅铁", "锰硅": "锰硅", "尿素": "尿素",
    "塑料": "塑料", "聚氯乙烯": "PVC", "纸浆": "纸浆", "原木": "原木", "粳米": "粳米",
}
WIDTH = 37      # 字号 2 每行可用的半角列数:2026-10-20 实测「5」+18 个汉字(37 列)整行显示;「铁矿2701 678.0 -0.66%」21 列
COMPACT = 0     # 0: 「螺纹 3215 +0.62%」 1: 去掉 %  2: 再去掉短名后的空格。实测宽度够,保留 %
CLOCK_MAX_NAMES = 4
MAX_NAMES = 12          # 12 行最长约 300 字节,低于插件 NavGlasses 的 384 字节 clip;futures-kb kb stream MAX_SYMBOLS 同步为 12
PAGE_SIZE = 6           # 轮播每页默认行数:2026-10-20 实测 max_lines 8 时短句可见 7 行、带页码的行情行只见 6 行(底部对齐挤掉首行);插件按设置传 page_size 5/6/7
PAGE_SECONDS = 5.0      # 轮播默认每页停留秒数
PAGE_SECONDS_RANGE = (2.0, 60.0)
FRAME_INTERVAL = 1.0
HEARTBEAT = 10.0


def split_label(label: str):
    """「铁矿石2701」→ ("铁矿石","2701");「铁矿石」→ ("铁矿石","")。"""
    i = len(label)
    while i > 0 and label[i - 1].isdigit():
        i -= 1
    return label[:i], label[i:]


def short(label: str) -> str:
    """显示名:品种短名 + 月份(铁矿2701);主连只有短名。"""
    name, yymm = split_label(label)
    if name in SHORT:
        return SHORT[name] + yymm
    return (name if len(name) <= 2 else name[:2]) + yymm


def price(v) -> str:
    if v is None:
        return "--"
    if v >= 100:
        return f"{v:.0f}" if v == int(v) else f"{v:.1f}"
    return f"{v:g}"


def chg(v, compact: int = COMPACT) -> str:
    if v is None:
        return "--"
    return f"{v:+.2f}" + ("" if compact >= 1 else "%")


def line(name: str, row: dict | None, compact: int = COMPACT) -> str:
    sep = "" if compact >= 2 else " "
    if not row or row.get("last") is None:
        return f"{short(name)}{sep}-- --"
    return f"{short(name)}{sep}{price(row['last'])} {chg(row.get('chg_pct'), compact)}"


def format_panel(rows: dict, names: list[str], asof: str = "", compact: int = COMPACT, clock: bool = True) -> str:
    lines = []
    if clock and asof and len(names) <= CLOCK_MAX_NAMES:
        lines.append(asof[-8:])
    for n in names:
        lines.append(line(n, rows.get(n), compact))
    return "\n".join(lines)


def pages(names: list[str], size: int = PAGE_SIZE) -> list[list[str]]:
    size = max(1, int(size or PAGE_SIZE))
    return [names[i:i + size] for i in range(0, len(names), size)] or [[]]


def page_of(elapsed: float, page_seconds: float, total: int) -> int:
    """轮播:开流后第 elapsed 秒该显示第几页(0 起)。"""
    if total <= 1 or page_seconds <= 0:
        return 0
    return int(elapsed // page_seconds) % total


def clamp_page_seconds(v) -> float:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return PAGE_SECONDS
    lo, hi = PAGE_SECONDS_RANGE
    return min(hi, max(lo, f))


def format_page(rows: dict, names: list[str], page: int, size: int = PAGE_SIZE, compact: int = COMPACT, asof: str = "", paused: bool = False) -> str:
    """轮播模式的一帧:第 page 页的行;多于一页时首行末尾加「 p/n」(暂停翻页时「 p/n 停」);只有一页时等同 format_panel(含 ≤4 品种时钟行)。"""
    ps = pages(names, size)
    total = len(ps)
    if total <= 1:
        return format_panel(rows, names, asof, compact)
    page = max(0, min(page, total - 1))
    lines = [line(n, rows.get(n), compact) for n in ps[page]]
    lines[0] = f"{lines[0]}  {page + 1}/{total}" + (" 停" if paused else "")
    return "\n".join(lines)


def asof_of(rows: dict) -> str:
    return max((r.get("time") or "" for r in rows.values()), default="")


class Stream:
    """一个 `kb stream` 子进程 + 最新快照。线程安全靠 cond。"""

    def __init__(self, cmd: list[str], cwd, names: list[str], log):
        self.cmd, self.cwd, self.names, self.log = list(cmd), cwd, list(names), log
        self.cond = threading.Condition()
        self.latest: dict[str, dict] = {}
        self.version = 0
        self.clients = 0
        self.error: str | None = None
        self.closed = False
        self.attempt = 0
        self.proc: subprocess.Popen | None = None
        self.last_stderr = ""
        self._spawn()

    def _spawn(self):
        self.proc = subprocess.Popen(self.cmd + self.names, cwd=self.cwd, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8")
        self.log.info("stream spawned pid=%s names=%s", self.proc.pid, ",".join(self.names))
        threading.Thread(target=self._read_err, args=(self.proc,), daemon=True).start()
        threading.Thread(target=self._read, args=(self.proc,), daemon=True).start()

    def _read_err(self, proc):
        for raw in proc.stderr:
            s = raw.strip()
            if s.startswith("{"):
                self.last_stderr = s

    def _read(self, proc):
        for raw in proc.stdout:
            try:
                row = json.loads(raw)
            except ValueError:
                continue
            name = row.get("name")
            if not name:
                continue
            with self.cond:
                self.latest[name] = row
                self.version += 1
                self.attempt = 0
                self.cond.notify_all()
        code = proc.wait()
        with self.cond:
            if self.closed:
                return
            if code == 2 or self.clients <= 0:
                self.error = self.last_stderr or f"行情源退出({code})"
                self.closed = True
                self.log.warning("stream ended code=%s err=%s", code, self.error)
                self.cond.notify_all()
                return
            self.attempt += 1
            delay = min(2 ** self.attempt, 30)
            self.log.warning("stream exited code=%s, respawn in %ss", code, delay)
        time.sleep(delay)
        with self.cond:
            if self.closed:
                return
        self._spawn()

    def close(self):
        with self.cond:
            self.closed = True
            proc = self.proc
            self.cond.notify_all()
        if proc is None:
            return
        try:
            proc.stdin.close()
        except Exception:
            pass
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                proc.kill()
        for f in (proc.stdout, proc.stderr):
            try:
                f.close()
            except Exception:
                pass
        self.log.info("stream closed pid=%s", proc.pid)


class StreamHub:
    """按品种元组复用 Stream;最后一个客户端走后 idle 秒回收。"""

    def __init__(self, cmd: list[str], cwd, log, idle: float = 30.0):
        self.cmd, self.cwd, self.log, self.idle = list(cmd), cwd, log, idle
        self.lock = threading.Lock()
        self.streams: dict[tuple, Stream] = {}
        self.timers: dict[tuple, threading.Timer] = {}

    def acquire(self, names: list[str]) -> Stream:
        key = tuple(names)
        with self.lock:
            t = self.timers.pop(key, None)
            if t:
                t.cancel()
            s = self.streams.get(key)
            if s is None or s.closed:
                # 2026-10-21 审查:换合约时别让没人用的旧子进程再挂 idle 秒——天勤免费账号并发会话有限(另有 kb quotes 在用)
                for k, other in list(self.streams.items()):
                    with other.cond:
                        idle = other.clients == 0
                    if k != key and idle:
                        t = self.timers.pop(k, None)
                        if t:
                            t.cancel()
                        del self.streams[k]
                        other.close()
                        self.log.info("stream closed (superseded) names=%s", ",".join(other.names))
                s = Stream(self.cmd, self.cwd, names, self.log)
                self.streams[key] = s
            with s.cond:
                s.clients += 1
            return s

    def release(self, s: Stream):
        key = tuple(s.names)
        with s.cond:
            s.clients = max(0, s.clients - 1)
            remaining = s.clients
        if remaining > 0:
            return

        def reap():
            with self.lock:
                self.timers.pop(key, None)
                with s.cond:
                    if s.clients > 0:
                        return
                if self.streams.get(key) is s:
                    del self.streams[key]
            s.close()
            self.log.info("stream closed (idle) names=%s", ",".join(s.names))

        with self.lock:
            old = self.timers.pop(key, None)  # 同一 key 只留一个回收计时器
            if old:
                old.cancel()
            t = threading.Timer(self.idle, reap)
            t.daemon = True
            self.timers[key] = t
            t.start()

    def active(self) -> int:
        with self.lock:
            return sum(1 for s in self.streams.values() if not s.closed)
