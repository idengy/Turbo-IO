package com.turboio.addon;

/** 眼镜语音备忘自动进随记的纯规则(bill 分支 2026-10-22,无 Android 依赖,可直接 javac 单测)。
 *  录音 = 官方 App 私有目录 `app_flutter/user_<uid>/audio/recordSync/<32 位十六进制>.opus`(r31 实机定位);
 *  同目录下的临时名(`*_temp.opus`、`*.raw.opus`、`*.fix.tmp.opus`)不是成品,不传。 */
public final class MemoPolicy {
    public enum Outcome { DONE, SKIP, RETRY }
    public static final long STABLE_MS = 3000;                 // 文件大小连续不变这么久才算同步写完
    public static final long MAX_BYTES = 10L * 1024 * 1024;    // 与网关 / suiji-bot 上限一致;48 kHz 双声道约 14 分钟(2026-10-21 由 2 MB 放大,2 MB 只够约 2 分 50 秒)
    public static final long BASELINE_AGE_MS = 30 * 60_000L;  // 首次开启时只把 30 分钟以前的录音当旧的(r33)
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

    /** 按入队顺序取第一条已到补传时间的录音(没到时间的不挡后面的,2026-10-21:失败那条留在队首曾把后面的录音全堵住);都没到返回 null。 */
    public static String next(java.util.List<String> order, java.util.Map<String, Long> retryAt, long now) {
        for (String id : order) { Long t = retryAt.get(id); if (t == null || t <= now) return id; }
        return null;
    }

    /** 第 n 次失败后的补传等待(1、2、5、10、30 分钟封顶);只在宿主进程活着时由 Handler 排程,不轮询目录。 */
    public static long retryDelay(int failures) {
        return RETRY_MS[Math.max(0, Math.min(failures - 1, RETRY_MS.length - 1))];
    }
}
