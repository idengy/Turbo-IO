import com.turboio.addon.MemoPolicy;
import com.turboio.addon.MemoPolicy.Outcome;

/** 语音备忘自动进随记的纯规则(2026-10-22):成品文件名识别、网关回应分类、补传退避。 */
public final class MemoTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    /** 在 at 处写一个 Ogg 页头(只填 magic、version、granule)。 */
    private static void page(byte[] b, int at, long granule) {
        b[at] = 'O'; b[at + 1] = 'g'; b[at + 2] = 'g'; b[at + 3] = 'S'; b[at + 4] = 0;
        for (int i = 0; i < 8; i++) b[at + 6 + i] = (byte) (granule >>> (8 * i));
    }
    public static void main(String[] a) {
        // r31 实机文件名:成品 = 32 位小写十六进制 + .opus
        check("63c425cc696e4a83a3f7578a0308b9d1".equals(MemoPolicy.memoId("63c425cc696e4a83a3f7578a0308b9d1.opus")));
        check(MemoPolicy.memoId("63c425cc696e4a83a3f7578a0308b9d1_temp.opus") == null);
        check(MemoPolicy.memoId("63c425cc696e4a83a3f7578a0308b9d1.raw.opus") == null);
        check(MemoPolicy.memoId("63c425cc696e4a83a3f7578a0308b9d1.fix.tmp.opus") == null);
        check(MemoPolicy.memoId("ogg_force_ch1_63c425cc696e4a83a3f7578a0308b9d1_1792600935331.opus") == null);
        check(MemoPolicy.memoId("VoiceMemo 2026-10-19 15 16.mp3") == null && MemoPolicy.memoId(null) == null);
        // 网关 / suiji-bot 回应
        check(MemoPolicy.outcome(200) == Outcome.DONE);
        check(MemoPolicy.outcome(400) == Outcome.SKIP && MemoPolicy.outcome(413) == Outcome.SKIP && MemoPolicy.outcome(422) == Outcome.SKIP);
        check(MemoPolicy.outcome(401) == Outcome.RETRY && MemoPolicy.outcome(502) == Outcome.RETRY && MemoPolicy.outcome(503) == Outcome.RETRY && MemoPolicy.outcome(-1) == Outcome.RETRY);
        // 退避 1、2、5、10、30 分钟封顶
        check(MemoPolicy.retryDelay(1) == 60_000 && MemoPolicy.retryDelay(3) == 300_000 && MemoPolicy.retryDelay(5) == 1_800_000 && MemoPolicy.retryDelay(99) == 1_800_000 && MemoPolicy.retryDelay(0) == 60_000);
        // 2026-10-21:没到补传时间的不挡后面的;全在等返回 null
        java.util.List<String> q = java.util.Arrays.asList("a", "b", "c");
        java.util.Map<String, Long> at = new java.util.HashMap<>();
        check("a".equals(MemoPolicy.next(q, at, 100)));
        at.put("a", 500L);
        check("b".equals(MemoPolicy.next(q, at, 100)) && "a".equals(MemoPolicy.next(q, at, 500)));
        at.put("b", 600L); at.put("c", 700L);
        check(MemoPolicy.next(q, at, 100) == null);
        // 上限 10 MB(48 kHz 双声道约 14 分钟)
        check(MemoPolicy.MAX_BYTES == 10L * 1024 * 1024);
        // r35:Ogg 时长 = 最后一页 granule / 48;前面的页不算
        byte[] tail = new byte[200];
        page(tail, 10, 48_000L * 5);            // 5 s
        page(tail, 120, 48_000L * 3723 + 480);  // 最后一页:3723.01 s
        check(MemoPolicy.oggDurationMs(tail) == 3_723_010);
        check(MemoPolicy.oggDurationMs(new byte[100]) == -1 && MemoPolicy.oggDurationMs(null) == -1);
        // r35:关闭期间录的不进随记
        long on = 1_000_000_000L;
        check(!MemoPolicy.recordedWhileOff(on - 999, 10, 0));                       // 从没关过:不拦
        check(MemoPolicy.recordedWhileOff(on + 600_000, 3_600_000, on));            // 1 小时会议,打开后 10 分钟才同步完:拦
        check(!MemoPolicy.recordedWhileOff(on + 120_000, 30_000, on));              // 打开后才录的 30 s 备忘:放行
        check(MemoPolicy.recordedWhileOff(on + 60_000, -1, on) && !MemoPolicy.recordedWhileOff(on + 11 * 60_000, -1, on));  // 时长未知:10 分钟内拦
        System.out.println("PASS " + checks + " memo checks");
    }
}
