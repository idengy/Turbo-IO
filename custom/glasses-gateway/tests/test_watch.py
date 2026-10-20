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

    def test_line_formats(self):
        row = {"last": 3215.0, "chg_pct": 0.62}
        self.assertEqual(watch.line("螺纹钢", row, compact=0), "螺纹 3215 +0.62%")
        self.assertEqual(watch.line("螺纹钢", row, compact=1), "螺纹 3215 +0.62")
        self.assertEqual(watch.line("螺纹钢", row, compact=2), "螺纹3215 +0.62")
        self.assertEqual(watch.line("铁矿石", {"last": 781.5, "chg_pct": -0.19}, 1), "铁矿 781.5 -0.19")
        self.assertEqual(watch.line("铜", {"last": 109110.0, "chg_pct": -0.67}), "铜 109110 -0.67%")  # 默认 compact=0,18 格 ≤ WIDTH
        self.assertLessEqual(watch.WIDTH, 19); self.assertEqual(watch.COMPACT, 0)
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
        while not s.closed and time.time() < deadline:
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
