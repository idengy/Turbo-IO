"""words.py / youdao_sync.py 单测:格宽折行、音标换行、超长拆帧、标尺帧、Deck 游标持久化与热重载、shuffle 不重复、两种输入解析。
跑法:python3 -m unittest discover -s custom/glasses-gateway/tests -v"""
import json
import pathlib
import sys
import tempfile
import time
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import words  # noqa: E402
import youdao_sync  # noqa: E402


class WidthTest(unittest.TestCase):
    def test_cells(self):
        self.assertEqual(words.cells("一二三"), 3)
        self.assertEqual(words.cells("ab"), 2 / words.ASCII_PER_CELL)
        self.assertEqual(words.cells("ˈɪə"), 3 / words.ASCII_PER_CELL)  # IPA 按半角计

    def test_wrap_cjk(self):
        lines = words.wrap("一" * 40, width=19)
        self.assertEqual([len(l) for l in lines], [19, 19, 2])

    def test_wrap_english_breaks_at_space(self):
        lines = words.wrap("the quick brown fox jumps over the lazy dog again and again", width=10)  # 10 格 = 20 半角
        self.assertTrue(all(words.cells(l) <= 10 for l in lines))
        self.assertTrue(all(not l.startswith(" ") and not l.endswith(" ") for l in lines))
        self.assertEqual(" ".join(lines), "the quick brown fox jumps over the lazy dog again and again")

    def test_wrap_hard_cuts_long_token(self):
        lines = words.wrap("a" * 50, width=10)
        self.assertEqual([len(l) for l in lines], [20, 20, 10])

    def test_wrap_mixed(self):
        lines = words.wrap("n. 意外发现珍奇事物的本领；机缘巧合 serendipity", width=19)
        self.assertTrue(all(words.cells(l) <= 19 for l in lines))
        self.assertEqual("".join(lines).replace(" ", ""), "n.意外发现珍奇事物的本领；机缘巧合serendipity")


class LayoutTest(unittest.TestCase):
    def test_short_word_one_frame(self):
        f = words.layout({"word": "apple", "phonetic": "ˈæpl", "trans": ["n. 苹果"]})
        self.assertEqual(f, ["apple  /ˈæpl/\nn. 苹果"])

    def test_phonetic_moves_to_second_line_when_too_wide(self):
        f = words.layout({"word": "incomprehensibility", "phonetic": "ɪnˌkɒmprɪˌhensəˈbɪləti", "trans": ["n. 不可理解"]})
        lines = f[0].split("\n")
        self.assertEqual(lines[0], "incomprehensibility")
        self.assertTrue(lines[1].startswith("/"))
        self.assertEqual(lines[-1], "n. 不可理解")

    def test_long_definition_splits_into_frames_keeping_word(self):
        trans = ["n. " + "释义" * 12] * 4  # 每条 2 行,共 8 行,首帧只能放 4 行
        f = words.layout({"word": "x", "phonetic": "", "trans": trans})
        self.assertEqual(len(f), 2)
        for frame in f:
            lines = frame.split("\n")
            self.assertLessEqual(len(lines), words.MAX_LINES)
            self.assertEqual(lines[0], "x")
            self.assertTrue(all(words.cells(l) <= words.WIDTH for l in lines))
        self.assertEqual(sum(len(fr.split("\n")) - 1 for fr in f), 8)

    def test_no_trans(self):
        self.assertEqual(words.layout({"word": "w", "trans": []}), ["w\n(无释义)"])

    def test_ruler_frames_fit_limits(self):
        for fr in words.ruler_frames():
            self.assertLessEqual(len(fr.split("\n")), words.MAX_LINES)
            self.assertLessEqual(len(fr), words.MAX_CHARS)
        self.assertIn("1234567890", words.ruler_frames()[0])

    def test_clamp_seconds(self):
        self.assertEqual(words.clamp_seconds("x"), words.DEFAULT_SECONDS)
        self.assertEqual(words.clamp_seconds(1), words.MIN_SECONDS)
        self.assertEqual(words.clamp_seconds(999), words.MAX_SECONDS)
        self.assertEqual(words.clamp_seconds("8"), 8)


def _book(root, entries):
    (root / "wordbook.json").write_text(json.dumps({"entries": entries}, ensure_ascii=False), encoding="utf-8")


class DeckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.tmp.name)
        self.entries = [{"id": str(i), "word": f"w{i}", "phonetic": "", "trans": ["t"], "book": "b", "added": i} for i in range(5)]
        _book(self.root, self.entries)

    def tearDown(self):
        self.tmp.cleanup()

    def test_empty(self):
        d = words.Deck(self.root / "nope")
        self.assertEqual(d.total(), 0)
        with self.assertRaises(LookupError):
            d.next()

    def test_seq_order_recent_first_and_wraps(self):
        d = words.Deck(self.root)
        got = [d.next()[0]["word"] for _ in range(6)]
        self.assertEqual(got, ["w4", "w3", "w2", "w1", "w0", "w4"])
        e, idx, total = d.next()
        self.assertEqual((e["word"], idx, total), ("w3", 2, 5))

    def test_cursor_persists_across_instances(self):
        d = words.Deck(self.root); d.next(); d.next()
        d2 = words.Deck(self.root)
        self.assertEqual(d2.next()[0]["word"], "w2")
        self.assertTrue((self.root / "state.json").exists())

    def test_reload_keeps_current_word(self):
        d = words.Deck(self.root); d.next(); d.next()  # current = w3, next would be w2
        time.sleep(0.02)
        new = [{"id": "9", "word": "w9", "phonetic": "", "trans": ["t"], "book": "b", "added": 9}] + self.entries
        _book(self.root, new)
        # 词库变了:游标停在 w3 上,下一个仍是 w2(而不是从 w9 重来)
        self.assertEqual(d.next()[0]["word"], "w2")
        self.assertEqual(d.total(), 6)

    def test_shuffle_covers_all_without_repeat(self):
        d = words.Deck(self.root)
        got = [d.next("shuffle")[0]["word"] for _ in range(5)]
        self.assertEqual(sorted(got), ["w0", "w1", "w2", "w3", "w4"])
        self.assertEqual(sorted(d.next("shuffle")[0]["word"] for _ in range(5)), ["w0", "w1", "w2", "w3", "w4"])

    def test_status(self):
        d = words.Deck(self.root)
        s = d.status()
        self.assertEqual(s["total"], 5); self.assertEqual(s["book_counts"], {"b": 5}); self.assertEqual(s["width"], words.WIDTH)


class SyncParseTest(unittest.TestCase):
    def test_split_trans(self):
        self.assertEqual(youdao_sync.split_trans("n. 苹果；苹果树\nvt. 吃"), ["n. 苹果", "苹果树", "vt. 吃"])
        self.assertEqual(youdao_sync.split_trans(""), [])

    def test_normalize_web_tolerates_fields(self):
        items = [{"itemId": "a1", "itemName": "apple", "trans": "n. 苹果", "phonetic": "ˈæpl", "bookId": "0", "modifiedTime": 1700000000000},
                 {"itemName": "", "trans": "skip"}, {"word": "pear", "translation": "n. 梨", "bookId": "7"}]
        out = youdao_sync.normalize_web(items, {"0": "默认", "7": "考研"})
        self.assertEqual(len(out), 2)
        self.assertEqual(out[0], {"id": "a1", "word": "apple", "phonetic": "ˈæpl", "trans": ["n. 苹果"], "book": "默认", "added": 1700000000})
        self.assertEqual(out[1]["book"], "考研"); self.assertEqual(out[1]["id"], "pear")

    def test_parse_xml(self):
        with tempfile.TemporaryDirectory() as t:
            p = pathlib.Path(t) / "x.xml"
            p.write_text('<?xml version="1.0"?><wordbook><item><word>apple</word><trans><![CDATA[n. 苹果；苹果树]]></trans><phonetic>ˈæpl</phonetic><tags>水果</tags></item>'
                         '<item><word></word></item></wordbook>', encoding="utf-8")
            out = youdao_sync.parse_xml(p)
            self.assertEqual(len(out), 1)
            self.assertEqual(out[0]["trans"], ["n. 苹果", "苹果树"]); self.assertEqual(out[0]["book"], "水果")
            target = youdao_sync.write_book(out, pathlib.Path(t) / "out")
            self.assertEqual(json.loads(target.read_text())["entries"][0]["word"], "apple")


if __name__ == "__main__":
    unittest.main()
