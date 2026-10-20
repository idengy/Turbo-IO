import com.turboio.addon.MarkdownText;

public final class MarkdownTextTest {
    private static int checks;
    private static void check(boolean v, String what) { if (!v) throw new AssertionError("check " + checks + ": " + what); checks++; }
    public static void main(String[] args) {
        String md = "---\ntitle: x\ntags: [a]\n---\n# 螺纹钢 周报\n\n> 引用 **粗体** 和 *斜体* 以及 `代码`\n\n- 第一条 [链接](https://x.y/z)\n- 第二条 ![图](a.png) [[页面|别名]] [[裸链]]\n1. 有序\n2) 有序二\n\n| 品种 | 价格 |\n|---|---|\n| 螺纹 | 3215 |\n\n```\nraw *code* 保留\n```\n\n---\n\n<b>html</b> ~~删除~~ 尾声\n\n\n\n最后一行   \n";
        MarkdownText.Result r = MarkdownText.convert(md);
        check(r.title.equals("螺纹钢 周报"), r.title);
        String t = r.text;
        check(!t.contains("title: x") && !t.contains("tags:"), "front matter 去掉");
        check(t.startsWith("螺纹钢 周报\n\n"), "标题在最前且后面空一行: " + t.substring(0, Math.min(20, t.length())));
        check(t.contains("引用 粗体 和 斜体 以及 代码"), "行内标记: " + t);
        check(t.contains("· 第一条 链接") && t.contains("· 第二条  别名 裸链"), "列表/链接/图片/双链: " + t);
        check(t.contains("1. 有序\n2. 有序二"), "有序列表: " + t);
        check(t.contains("品种  价格\n螺纹  3215"), "表格拍平: " + t);
        check(!t.contains("---") && !t.contains("|"), "分隔行/水平线去掉: " + t);
        check(t.contains("raw *code* 保留") && !t.contains("```"), "代码块: " + t);
        check(t.contains("html 删除 尾声"), "html/删除线: " + t);
        check(!t.contains("\n\n\n"), "空行压缩");
        check(t.endsWith("最后一行"), "行尾空白去掉、末尾无空行");
        // 识别
        check(MarkdownText.looksLikeMarkdown(md), "looks md");
        check(!MarkdownText.looksLikeMarkdown("第一章 风起\n\n天色将晚，他沿着河走。\n第二章 云涌\n她没有回头。"), "小说不像 md");
        check(MarkdownText.convert("没有标题的正文第一行\n第二行").title.equals("没有标题的正文第一行"), "无标题取首行");
        check(MarkdownText.convert("").title.equals("导入的 Markdown"), "空文本默认书名");
        // 单星号不该吃掉乘法/价格
        check(MarkdownText.inline("3*4=12 和 5 * 6").equals("3*4=12 和 5 * 6"), "星号不误伤: " + MarkdownText.inline("3*4=12 和 5 * 6"));
        check(MarkdownText.inline("snake_case_name").equals("snake_case_name"), "下划线不误伤");
        System.out.println("PASS " + checks + " markdown checks");
    }
}
