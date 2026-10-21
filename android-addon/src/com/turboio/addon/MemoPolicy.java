package com.turboio.addon;

/** 眼镜语音备忘自动进随记的纯规则(bill 分支 2026-10-22,无 Android 依赖,可直接 javac 单测)。
 *  录音 = 官方 App 私有目录 `app_flutter/user_<uid>/audio/recordSync/<32 位十六进制>.opus`(r31 实机定位);
 *  同目录下的临时名(`*_temp.opus`、`*.raw.opus`、`*.fix.tmp.opus`)不是成品,不传。 */
public final class MemoPolicy {
    public enum Outcome { DONE, SKIP, RETRY }
    public static final long STABLE_MS = 3000;                 // 文件大小连续不变这么久才算同步写完
    public static final long MAX_BYTES = 2L * 1024 * 1024;     // 与网关 / suiji-bot 上限一致
    private static final long[] RETRY_MS = {60_000, 120_000, 300_000, 600_000, 1_800_000};

    /** 成品录音文件名 → 录音 id;不是成品返回 null。 */
    public static String memoId(String name) {
        if (name == null || !name.matches("[0-9a-f]{32}\\.opus")) return null;
        return name.substring(0, 32);
    }

    /** 网关回应 → 处理结果:2xx 写入或已去重 = DONE;400/413/422 内容问题重传也没用 = SKIP;401/403/5xx/网络 = RETRY。 */
    public static Outcome outcome(int httpCode) {
        if (httpCode >= 200 && httpCode < 300) return Outcome.DONE;
        if (httpCode == 400 || httpCode == 413 || httpCode == 422) return Outcome.SKIP;
        return Outcome.RETRY;
    }

    /** 第 n 次失败后的补传等待(1、2、5、10、30 分钟封顶);只在宿主进程活着时由 Handler 排程,不轮询目录。 */
    public static long retryDelay(int failures) {
        return RETRY_MS[Math.max(0, Math.min(failures - 1, RETRY_MS.length - 1))];
    }
}
