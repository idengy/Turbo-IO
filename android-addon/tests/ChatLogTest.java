import com.turboio.addon.ChatLog;
import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/** 问答对话存档滚动 24 小时(r35)。 */
public final class ChatLogTest {
    private static int checks;
    private static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError("check " + checks + ": " + what); }
    public static void main(String[] a) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        long now = f.parse("2026-10-22 12:00:00").getTime();
        String old = ChatLog.section("2026-10-21 11:59:59", "昨天的问题", "过期回答", ChatLog.OWN);
        String mid = ChatLog.section("2026-10-21 12:00:00", "正好 24 小时", "## 回答里的小标题\n\n正文", ChatLog.OWN);
        String fresh = ChatLog.section("2026-10-22 11:00:00", "螺纹钢多少钱", "3215", ChatLog.OWN);
        String md = old + mid + fresh;
        String kept = ChatLog.prune(md, now);
        check(kept.equals(mid + fresh), "剪掉 24 小时以前的一节,其余逐字不变:\n" + kept);
        check(ChatLog.prune(kept, now).equals(kept), "再剪一次不变");
        check(ChatLog.prune("", now).isEmpty() && ChatLog.prune(old, now).isEmpty(), "空 / 全过期");
        check(ChatLog.prune("没有时间头的残片\n" + fresh, now).equals(fresh), "头部残片丢掉");
        List<ChatLog.Entry> es = ChatLog.entries(kept);
        check(es.size() == 2, "回答里的 ## 小标题不切节: " + es.size());
        check(es.get(0).question.equals("螺纹钢多少钱") && es.get(0).answer.equals("3215") && es.get(0).stamp.equals("2026-10-22 11:00:00"), "最新的在前");
        check(es.get(1).answer.equals("## 回答里的小标题\n\n正文"), "回答原样: " + es.get(1).answer);
        // 文件:追加后剪,旧的掉出去
        File file = File.createTempFile("chatlog", ".md"); file.deleteOnExit();
        Files.write(file.toPath(), (old + mid).getBytes("UTF-8"));
        ChatLog.append(file, "2026-10-22 11:00:00", "螺纹钢多少钱", "3215", ChatLog.OWN, now);
        check(ChatLog.read(file).equals(mid + fresh), "append 后剪");
        check(!new File(file.getPath() + ".tmp").exists(), "临时文件已 rename");
        // r38:官方助手的轮次、旧格式(上游原文)兼容、流式片段拼接
        String upstream = "\n## 2026-10-22 11:30:00\n\n### 用户\n\n旧格式\n\n### Turbo IO\n\n旧回答\n";
        String official = ChatLog.section("2026-10-22 11:40:00", "今天天气", "晴，22 度", ChatLog.OFFICIAL);
        List<ChatLog.Entry> mixed = ChatLog.entries(upstream + official);
        check(mixed.get(0).source.equals(ChatLog.OFFICIAL) && mixed.get(0).answer.equals("晴，22 度") && mixed.get(0).question.equals("今天天气"), "官方助手条目");
        check(mixed.get(1).source.equals(ChatLog.OWN) && mixed.get(1).answer.equals("旧回答"), "上游旧格式按自有模型解析");
        check(ChatLog.merge("", "晴").equals("晴") && ChatLog.merge("晴", "晴，22").equals("晴，22") && ChatLog.merge("晴，22", "度").equals("晴，22度")
            && ChatLog.merge("晴，22", "晴").equals("晴，22") && ChatLog.merge("晴", null).equals("晴"), "增量与全文两种片段都能拼");
        System.out.println("PASS " + checks + " chat log checks");
    }
}
