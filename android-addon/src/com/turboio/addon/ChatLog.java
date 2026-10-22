package com.turboio.addon;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 问答对话存档(bill 分支 r35,2026-10-21):上游 `TurboAddon.archive()` 写的 `files/turboio_android/conversations.md`
 *  改为只留最近 24 小时、循环滚动。格式沿用上游:`## yyyy-MM-dd HH:mm:ss` / `### 用户` / `### Turbo IO`。
 *  无 Android 依赖,可直接 javac 单测。 */
public final class ChatLog {
    public static final long WINDOW_MS = 24 * 3600_000L;
    public static final long MAX_BYTES = 8L * 1024 * 1024;     // 上游上限:剪完 24 h 还超就丢最旧的
    private static final Pattern HEADER = Pattern.compile("## \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    private static final String Q = "### 用户\n\n";
    /** 回答小标题 = 来源:自有模型沿用上游的「Turbo IO」;r38 起官方助手自己回答的记为「官方助手」。 */
    public static final String OWN = "Turbo IO", OFFICIAL = "官方助手";
    private static final Pattern ANSWER = Pattern.compile("\n\n### (" + OWN + "|" + OFFICIAL + ")\n\n");

    public static final class Entry {
        public final String stamp, question, answer, source; public final long time;
        Entry(String s, long t, String q, String a, String src) { stamp = s; time = t; question = q; answer = a; source = src; }
    }

    private static SimpleDateFormat format() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT); }

    /** 一轮问答的 md 片段(自有模型时与上游 archive 逐字相同)。 */
    public static String section(String stamp, String question, String answer, String source) {
        return "\n## " + stamp + "\n\n" + Q + question + "\n\n### " + source + "\n\n" + answer + "\n";
    }

    /** 流式回答片段拼全文:片段可能是增量,也可能是到目前为止的全文(与 VoiceSpeech.delta 同一判断)。 */
    public static String merge(String sofar, String chunk) {
        if (chunk == null || chunk.isEmpty()) return sofar;
        if (chunk.startsWith(sofar)) return chunk;
        if (sofar.startsWith(chunk)) return sofar;
        return sofar + chunk;
    }

    /** 按时间头切节;回答里自带的「## 小标题」不是时间头,算正文。返回 [时间头行, 整节文本] 按文件顺序。 */
    private static List<String[]> sections(String md) {
        List<String[]> out = new ArrayList<>();
        String[] lines = md.split("\n", -1);
        StringBuilder cur = null; String head = null;
        for (String line : lines) {
            if (HEADER.matcher(line).matches()) {
                if (cur != null) out.add(new String[]{head, cur.toString()});
                head = line; cur = new StringBuilder(line).append('\n');
            } else if (cur != null) cur.append(line).append('\n');
        }
        if (cur != null) out.add(new String[]{head, cur.toString()});
        return out;
    }

    private static long time(String head) {
        try { return format().parse(head.substring(3)).getTime(); } catch (ParseException e) { return -1; }
    }

    /** 只留 now 前 24 小时内的节;总长仍超 MAX_BYTES 就继续丢最旧的。时间头解析失败的节当作过期。 */
    public static String prune(String md, long now) {
        List<String[]> keep = new ArrayList<>();
        for (String[] s : sections(md)) { long t = time(s[0]); if (t >= 0 && now - t <= WINDOW_MS) keep.add(s); }
        long size = 0; for (String[] s : keep) size += s[1].getBytes(StandardCharsets.UTF_8).length;
        while (size > MAX_BYTES && !keep.isEmpty()) size -= keep.remove(0)[1].getBytes(StandardCharsets.UTF_8).length;
        if (keep.isEmpty()) return "";
        StringBuilder b = new StringBuilder("\n"); for (String[] s : keep) b.append(s[1]);   // 节与节之间的空行留在前一节末尾
        String out = b.toString();
        return out.endsWith("\n\n") ? out.substring(0, out.length() - 1) : out;
    }

    /** 解析成条目,最新的在前。 */
    public static List<Entry> entries(String md) {
        List<Entry> out = new ArrayList<>();
        for (String[] s : sections(md)) {
            String body = s[1].substring(s[0].length() + 1);
            int q = body.indexOf(Q);
            Matcher m = ANSWER.matcher(body);
            boolean found = m.find(q < 0 ? 0 : q);
            String question = q >= 0 && found && m.start() > q ? body.substring(q + Q.length(), m.start()) : "";
            String answer = found ? body.substring(m.end()) : body;
            out.add(new Entry(s[0].substring(3), time(s[0]), question.trim(), answer.trim(), found ? m.group(1) : OWN));
        }
        Collections.reverse(out);
        return out;
    }

    public static String read(File file) {
        if (!file.isFile()) return "";
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) { return ""; }
    }

    /** 剪到 24 小时后原子写回(临时文件 + rename);内容没变就不写。返回剪后的内容。 */
    public static String pruneFile(File file, long now) throws IOException {
        String old = read(file), md = prune(old, now);
        if (!md.equals(old)) write(file, md);
        return md;
    }

    /** 追加一轮再剪。 */
    public static void append(File file, String stamp, String question, String answer, String source, long now) throws IOException {
        write(file, prune(read(file) + section(stamp, question, answer, source), now));
    }

    private static void write(File file, String md) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { w.write(md); }
        if (!tmp.renameTo(file)) { tmp.delete(); throw new IOException("rename"); }
    }
}
