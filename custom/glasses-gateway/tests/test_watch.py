"""watch.py 单测:短名无碰撞、面板排版规则、Stream/StreamHub 用假子命令验证解析与回收。
跑法:python3 -m unittest discover -s custom/glasses-gateway/tests -v"""
import json
import logging
import pathlib
import subprocess
import sys
import time
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import watch  # noqa: E402

KB_ROOT = pathlib.Path("/Users/dyb/PROJECT/futures-kb")


def kb_names():
    out = subprocess.run([str(KB_ROOT / "venv/bin/python"), "-c", "import json;from kb.varieties import VARIETIES;print(json.dumps(list(VARIETIES),ensure_ascii=False))"],
                         cwd=KB_ROOT, capture_output=True, text=True, timeout=30)
    return json.loads(out.stdout)


class FormatTest(unittest.TestCase):
    def test_short_names_unique_across_all_varieties(self):
        names = kb_names()
        shorts = [watch.short(n) for n in names]
        dup = {s for s in shorts if shorts.count(s) > 1}
        self.assertEqual(dup, set(), f"短名碰撞: {dup}")
        self.assertTrue(all(len(s) <= 3 for s in shorts), [s for s in shorts if len(s) > 3])

    def test_contract_labels(self):
        self.assertEqual(watch.split_label("铁矿石2701"), ("铁矿石", "2701"))
        self.assertEqual(watch.split_label("铁矿石"), ("铁矿石", ""))
        self.assertEqual(watch.short("铁矿石2701"), "铁矿2701")
        self.assertEqual(watch.short("铜2611"), "铜2611")
        self.assertEqual(watch.line("铁矿石2701", {"last": 678.0, "chg_pct": -0.66}), "铁矿2701 678 -0.66%")
        self.assertLessEqual(len("铁矿2701 678.0 -0.66%") + 2, watch.WIDTH)  # 2 个汉字各占 2 列

    def test_line_formats(self):
        row = {"last": 3215.0, "chg_pct": 0.62}
        self.assertEqual(watch.line("螺纹钢", row, compact=0), "螺纹 3215 +0.62%")
        self.assertEqual(watch.line("螺纹钢", row, compact=1), "螺纹 3215 +0.62")
        self.assertEqual(watch.line("螺纹钢", row, compact=2), "螺纹3215 +0.62")
        self.assertEqual(watch.line("铁矿石", {"last": 781.5, "chg_pct": -0.19}, 1), "铁矿 781.5 -0.19")
        self.assertEqual(watch.line("铜", {"last": 109110.0, "chg_pct": -0.67}), "铜 109110 -0.67%")  # 默认 compact=0,18 格 ≤ WIDTH
        self.assertEqual(watch.WIDTH, 37); self.assertEqual(watch.COMPACT, 0)
        self.assertEqual(watch.line("豆粕", {"last": 2890.0, "chg_pct": None}, 1), "豆粕 2890 --")
        self.assertEqual(watch.line("黄金", None, 1), "黄金 -- --")
        self.assertEqual(watch.line("黄金", {"last": None}, 1), "黄金 -- --")

    def test_clock_line_only_up_to_four_names(self):
        rows = {n: {"last": 100.0 + i, "chg_pct": 0.1 * i, "time": f"2026-10-20 10:31:0{i}"} for i, n in enumerate(["螺纹钢", "铁矿石", "焦煤", "豆粕", "铜"])}
        four = watch.format_panel(rows, ["螺纹钢", "铁矿石", "焦煤", "豆粕"], watch.asof_of(rows))
        five = watch.format_panel(rows, ["螺纹钢", "铁矿石", "焦煤", "豆粕", "铜"], watch.asof_of(rows))
        self.assertEqual(four.split("\n")[0], "10:31:04")
        self.assertEqual(len(four.split("\n")), 5)
        self.assertEqual(len(five.split("\n")), 5)
        self.assertTrue(five.startswith("螺纹 "))
        self.assertEqual(watch.format_panel(rows, ["铜"], "").split("\n"), ["铜 104 +0.40%"])  # 无 asof 不加时钟

    def test_pages_and_rotation(self):
        names = [f"n{i}" for i in range(12)]
        self.assertEqual(watch.MAX_NAMES, 12)
        self.assertEqual([len(p) for p in watch.pages(names, 5)], [5, 5, 2])
        self.assertEqual(watch.pages([], 5), [[]])
        self.assertEqual([watch.page_of(t, 5.0, 3) for t in (0, 4.9, 5, 10, 15, 29.9)], [0, 0, 1, 2, 0, 2])
        self.assertEqual(watch.page_of(100, 5.0, 1), 0)
        self.assertEqual(watch.clamp_page_seconds("abc"), watch.PAGE_SECONDS)
        self.assertEqual((watch.clamp_page_seconds("0.5"), watch.clamp_page_seconds("999")), watch.PAGE_SECONDS_RANGE)

    def test_format_page_marker_and_single_page_fallback(self):
        rows = {n: {"last": 100 + i, "chg_pct": 0.1 * i, "time": "2026-10-20 10:13:06"} for i, n in enumerate(["螺纹钢", "铁矿石", "焦煤", "豆粕", "铜", "黄金", "白银"])}
        names = list(rows)
        p1 = watch.format_page(rows, names, 0, 5).split("\n")
        p2 = watch.format_page(rows, names, 1, 5).split("\n")
        self.assertEqual(len(p1), 5); self.assertEqual(len(p2), 2)
        self.assertTrue(p1[0].endswith("  1/2") and p2[0].endswith("  2/2"), (p1[0], p2[0]))
        self.assertTrue(p2[0].startswith("黄金") and p2[1].startswith("白银"))
        self.assertEqual(watch.format_page(rows, names, 9, 5), watch.format_page(rows, names, 1, 5))  # 越界钉到末页
        # 一页装得下 → 与 format_panel 一致(含 ≤4 品种时钟行)
        four = names[:4]
        self.assertEqual(watch.format_page(rows, four, 0, 5, asof=watch.asof_of(rows)), watch.format_panel(rows, four, watch.asof_of(rows)))
        # 12 行最长的一帧仍在插件 384 字节 clip 之内
        worst = {f"低硫燃料油27{i:02d}": {"last": 12345.5, "chg_pct": -10.25} for i in range(12)}
        self.assertLess(len(watch.format_panel(worst, list(worst), "").encode()), 384)

    def test_order_follows_request(self):
        rows = {"铜": {"last": 1.0, "chg_pct": 0.0}, "螺纹钢": {"last": 2.0, "chg_pct": 0.0}}
        self.assertEqual(watch.format_panel(rows, ["铜", "螺纹钢"], "", clock=False).split("\n")[0][:1], "铜")


FAKE = [sys.executable, "-u", "-c",
        "import sys,json\n"
        "names=sys.argv[1:]\n"
        "for i,n in enumerate(names): print(json.dumps({'name':n,'last':100.0+i,'chg_pct':0.5,'time':'2026-10-20 10:00:00'},ensure_ascii=False),flush=True)\n"
        "sys.stdin.read()\n"]


class StreamTest(unittest.TestCase):
    def test_hub_parses_lines_and_reaps_after_release(self):
        log = logging.getLogger("test")
        hub = watch.StreamHub(FAKE, None, log, idle=0.3)
        s = hub.acquire(["螺纹钢", "铁矿石"])
        with s.cond:
            deadline = time.time() + 5
            while s.version < 2 and time.time() < deadline:
                s.cond.wait(0.2)
        self.assertEqual(s.version, 2)
        self.assertEqual(s.latest["铁矿石"]["last"], 101.0)
        self.assertIs(hub.acquire(["螺纹钢", "铁矿石"]), s)
        self.assertEqual(s.clients, 2)
        hub.release(s); hub.release(s)
        deadline = time.time() + 5
        # close() 先置 closed 唤醒等待者、再等子进程退出:两件事都要等到,否则偶发 poll() 还是 None(2026-10-20 修)
        while (not s.closed or s.proc.poll() is None) and time.time() < deadline:
            time.sleep(0.1)
        self.assertTrue(s.closed)
        self.assertIsNotNone(s.proc.poll())
        self.assertEqual(hub.active(), 0)
        s2 = hub.acquire(["螺纹钢", "铁矿石"])
        self.assertIsNot(s2, s)
        hub.release(s2)

    def test_exit_two_sets_error(self):
        log = logging.getLogger("test")
        bad = [sys.executable, "-c", "import sys;sys.stderr.write('{\"error\":\"unknown_names\"}\\n');sys.exit(2)"]
        hub = watch.StreamHub(bad, None, log, idle=0.3)
        s = hub.acquire(["x"])
        with s.cond:
            deadline = time.time() + 5
            while s.error is None and time.time() < deadline:
                s.cond.wait(0.2)
        self.assertIn("unknown_names", s.error or "")
        hub.release(s)


if __name__ == "__main__":
    unittest.main()
