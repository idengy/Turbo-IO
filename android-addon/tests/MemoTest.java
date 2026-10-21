import com.turboio.addon.MemoPolicy;
import com.turboio.addon.MemoPolicy.Outcome;

/** 语音备忘自动进随记的纯规则(2026-10-22):成品文件名识别、网关回应分类、补传退避。 */
public final class MemoTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
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
        System.out.println("PASS " + checks + " memo checks");
    }
}
