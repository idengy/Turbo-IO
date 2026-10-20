package com.turboio.addon;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown → 眼镜可读纯文本(bill 分支 2026-10-20,给微信读书页「新增本机 TXT」导入 .md 用;纯 Java,javac 单测)。
 *  做的事:去 YAML front matter;代码块只去围栏保留内容;标题去 #;列表「- * +」→「· 」、有序号保留;引用去 >;
 *  粗斜体/行内代码/删除线去标记;图片去掉、链接只留文字;Obsidian [[页|别名]] 取别名;表格拍平成「a  b」、分隔行丢弃;
 *  水平线丢弃;HTML 标签去掉;连续空行压成一个。第一个标题(或第一行非空短句)作为书名。 */
public final class MarkdownText {
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern HR = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");
    private static final Pattern WIKI = Pattern.compile("\\[\\[([^\\]|]*)(?:\\|([^\\]]*))?\\]\\]");
    private static final Pattern TAG = Pattern.compile("</?[A-Za-z][^>]{0,80}>");
    private static final Pattern CODE = Pattern.compile("`([^`]*)`");
    private static final Pattern STRONG = Pattern.compile("(\\*\\*|__)(.+?)\\1");
    private static final Pattern EM = Pattern.compile("(?<![\\w*])(\\*|_)([^*_\\n]+?)\\1(?![\\w*])");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");

    public static final class Result { public final String title, text; Result(String t, String x) { title = t; text = x; } }

    /** 像 Markdown 吗:有标题行 / 围栏 / 链接 / 粗体 / 表格任一即认。 */
    public static boolean looksLikeMarkdown(String s) {
        int hits = 0;
        for (String line : s.split("\n", -1)) {
            if (HEADING.matcher(line).matches() || FENCE.matcher(line).find() || TABLE_SEP.matcher(line).matches()) hits++;
            else if (LINK.matcher(line).find() || STRONG.matcher(line).find() || BULLET.matcher(line).matches()) hits++;
            if (hits >= 2) return true;
        }
        return hits >= 1 && s.length() < 2000;
    }

    public static Result convert(String md) {
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> out = new ArrayList<>();
        String title = null;
        int i = 0;
        if (lines.length > 0 && lines[0].trim().equals("---")) {            // YAML front matter
            int j = 1; while (j < lines.length && !lines[j].trim().equals("---")) j++;
            if (j < lines.length) i = j + 1;
        }
        boolean inFence = false;
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (FENCE.matcher(line).find()) { inFence = !inFence; continue; }
            if (inFence) { out.add(line); continue; }
            if (TABLE_SEP.matcher(line).matches() || HR.matcher(line).matches()) continue;
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                String t = inline(h.group(2));
                if (title == null && !t.isEmpty()) title = t;
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) out.add("");
                out.add(t); out.add(""); continue;
            }
            Matcher q = QUOTE.matcher(line); if (q.matches()) line = q.group(1);
            Matcher b = BULLET.matcher(line);
            Matcher o = ORDERED.matcher(line);
            if (b.matches()) line = b.group(1) + "· " + b.group(2);
            else if (o.matches()) line = o.group(1) + o.group(2) + ". " + o.group(3);
            if (line.trim().startsWith("|") && line.trim().endsWith("|")) {            // 表格行拍平
                String[] cells = line.trim().substring(1, line.trim().length() - 1).split("\\|");
                StringBuilder sb = new StringBuilder();
                for (String c : cells) { String v = inline(c.trim()); if (v.isEmpty()) continue; if (sb.length() > 0) sb.append("  "); sb.append(v); }
                line = sb.toString();
            } else line = inline(line);
            if (line.trim().isEmpty()) { if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) out.add(""); continue; }
            out.add(line.replaceAll("\\s+$", ""));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        while (!out.isEmpty() && out.get(0).isEmpty()) out.remove(0);
        if (title == null) for (String l : out) if (!l.trim().isEmpty()) { title = l.trim().length() > 40 ? l.trim().substring(0, 40) : l.trim(); break; }
        StringBuilder sb = new StringBuilder();
        for (String l : out) { if (sb.length() > 0) sb.append('\n'); sb.append(l); }
        return new Result(title == null ? "导入的 Markdown" : title, sb.toString());
    }

    public static String inline(String s) {
        s = IMAGE.matcher(s).replaceAll("");
        Matcher w = WIKI.matcher(s);                       // 手写循环:Matcher.replaceAll(Function) 是 Java 9 API,不依赖 Android 版本
        if (w.find()) { StringBuffer sb = new StringBuffer(); do { String alias = w.group(2); w.appendReplacement(sb, Matcher.quoteReplacement(alias != null && !alias.isEmpty() ? alias : w.group(1))); } while (w.find()); w.appendTail(sb); s = sb.toString(); }
        s = LINK.matcher(s).replaceAll("$1");
        s = CODE.matcher(s).replaceAll("$1");
        s = STRONG.matcher(s).replaceAll("$2");
        s = EM.matcher(s).replaceAll("$2");
        s = STRIKE.matcher(s).replaceAll("$1");
        s = TAG.matcher(s).replaceAll("");
        return s;
    }
}
