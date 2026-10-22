"""transcript.py 单测:分享页 DOM 解析、说话人合并、文件名清洗、链接校验。DOM 是合成的(结构照官方分享页,内容虚构)。
跑法:python3 -m unittest discover -s custom/glasses-gateway/tests -v"""
import pathlib
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import transcript  # noqa: E402


def slice_(speaker, time, text):
    return (f'<article class="slice-item" data-active="false"><div class="slice-header">'
            f'<span class="speaker-chip" title="{speaker}">{speaker}</span>'
            f'<button class="mark-time slice-time" type="button">{time}</button>'
            f'<span class="speaker-indicator"><canvas width="20" height="20"></canvas></span></div>'
            f'<p class="slice-text" data-active="false">{text}</p></article>')


DOM = ('<html><body><div id="root"><div class="session-detail"><div class="session-banner"><img class="session-banner__image" src="x.png" alt=""></div>'
       '<header class="title-header"><h1 class="title-header__title">周例会 纪要</h1><div class="title-header__date">2026/10/2 9:05</div></header>'
       '<section class="record-section"><h2 class="record-section__title"><span>大纲</span></h2><div class="outline-list">'
       '<button class="outline-item" type="button"><span class="outline-item__time">00:00:00</span><span class="outline-item__content">讨论 豆粕 &amp; 菜粕 价差</span></button>'
       '<button class="outline-item" type="button"><span class="outline-item__time">00:05:10</span><span class="outline-item__content">确定下周计划</span></button>'
       '</div></section><section class="record-section transcript-section"><div class="transcript-list">'
       + slice_("说话人1", "00:00:00", "第一段，价差走阔。")
       + slice_("说话人1", "00:02:30", "第二段，继续观察。")
       + slice_("说话人2", "00:05:10", "下周<b>先</b>减仓。")
       + slice_("说话人1", "00:06:00", "好。")
       + '<article class="slice-item"><div class="slice-header"><span class="speaker-chip">说话人3</span></div><p class="slice-text">  </p></article>'
       + '</div></section><div class="ai-disclaimer">内容由 AI 生成，仅供参考</div></div></div></body></html>')


class ParseTest(unittest.TestCase):
    def test_fields(self):
        d = transcript.parse(DOM)
        self.assertEqual(d["title"], "周例会 纪要")
        self.assertEqual(d["date"], "2026/10/2 9:05")
        self.assertEqual(d["outline"], [["00:00:00", "讨论 豆粕 & 菜粕 价差"], ["00:05:10", "确定下周计划"]])
        self.assertEqual(len(d["slices"]), 4)                     # 空正文的段丢掉
        self.assertEqual(d["slices"][2], {"speaker": "说话人2", "time": "00:05:10", "text": "下周先减仓。"})

    def test_markdown(self):
        name, md = transcript.to_markdown(transcript.parse(DOM), "https://share.rayneo.cn/AbCd1234")
        self.assertEqual(name, "2026-10-02 周例会 纪要.md")
        self.assertTrue(md.startswith("# 周例会 纪要\n\n- 录音时间:2026/10/2 9:05\n- 来源:https://share.rayneo.cn/AbCd1234\n\n## 大纲\n\n- `00:00:00` 讨论 豆粕 & 菜粕 价差\n"))
        self.assertIn("## 转写原文\n\n**说话人1** `00:00:00`\n第一段，价差走阔。\n\n`00:02:30`\n第二段，继续观察。\n\n**说话人2** `00:05:10`\n下周先减仓。\n\n**说话人1** `00:06:00`\n好。\n", md)
        self.assertTrue(md.endswith("好。\n"))

    def test_empty_page(self):
        d = transcript.parse('<html><body><div id="app-loading">加载中</div></body></html>')
        self.assertEqual((d["title"], d["slices"], d["outline"]), ("", [], []))
        name, md = transcript.to_markdown(d, "https://share.rayneo.cn/x")
        self.assertEqual(name, "录音转写.md")


class HelpersTest(unittest.TestCase):
    def test_filename(self):
        self.assertEqual(transcript.filename('a/b:c*"d"?', "2026/1/9 10:00"), "2026-01-09 a b c d.md")
        self.assertEqual(transcript.filename("", ""), "录音转写.md")

    def test_valid_url(self):
        self.assertTrue(transcript.valid_url("https://share.rayneo.cn/4egIJUiY"))
        self.assertTrue(transcript.valid_url(" https://share.rayneo.cn/4egIJUiY/ "))
        for bad in ("http://share.rayneo.cn/4egIJUiY", "https://share.rayneo.cn.evil.com/4egIJUiY",
                    "https://share.rayneo.cn/4egIJUiY?x=1", "https://share.rayneo.cn/../etc", "https://example.com/4egIJUiY", ""):
            self.assertFalse(transcript.valid_url(bad), bad)


if __name__ == "__main__":
    unittest.main()
