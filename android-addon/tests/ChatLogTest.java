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
        String old = ChatLog.section("2026-10-21 11:59:59", "昨天的问题", "过期回答");
        String mid = ChatLog.section("2026-10-21 12:00:00", "正好 24 小时", "## 回答里的小标题\n\n正文");
        String fresh = ChatLog.section("2026-10-22 11:00:00", "螺纹钢多少钱", "3215");
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
        ChatLog.append(file, "2026-10-22 11:00:00", "螺纹钢多少钱", "3215", now);
        check(ChatLog.read(file).equals(mid + fresh), "append 后剪");
        check(!new File(file.getPath() + ".tmp").exists(), "临时文件已 rename");
        System.out.println("PASS " + checks + " chat log checks");
    }
}
