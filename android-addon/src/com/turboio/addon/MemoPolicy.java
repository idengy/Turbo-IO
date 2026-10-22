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

    /** 兜底:读不出时长时,开关打开后这么久内同步到的录音也算关闭期间录的(r35)。 */
    public static final long UNKNOWN_DURATION_GRACE_MS = 10 * 60_000L;

    /** Ogg Opus 文件尾部字节 → 时长毫秒(最后一页的 granule position,Opus 恒为 48 kHz);认不出返回 -1。
     *  只要尾部 64 KB:Ogg 页最大约 64 KB,最后一页的页头一定在里面。 */
    public static long oggDurationMs(byte[] tail) {
        if (tail == null) return -1;
        for (int i = tail.length - 27; i >= 0; i--) {
            if (tail[i] != 'O' || tail[i + 1] != 'g' || tail[i + 2] != 'g' || tail[i + 3] != 'S' || tail[i + 4] != 0) continue;
            long g = 0;
            for (int b = 7; b >= 0; b--) g = (g << 8) | (tail[i + 6 + b] & 0xff);
            return g > 0 ? g / 48 : -1;   // 48 个采样 = 1 ms
        }
        return -1;
    }

    /** 关闭开关期间录的?(r35,2026-10-21 Bill 定:开会前关掉,打开后这段录音也不能进随记)
     *  打开那一刻已有的录音在 enable 时整体记为已处理;这里管打开之后才同步到的:估算开始时间 = 同步完成时间 − 时长,早于打开时刻就算。
     *  从没关过(onAt ≤ 0)不拦。时长未知时退而看同步时间是否落在打开后 10 分钟内。 */
    public static boolean recordedWhileOff(long mtime, long durationMs, long onAt) {
        if (onAt <= 0) return false;
        if (durationMs < 0) return mtime < onAt + UNKNOWN_DURATION_GRACE_MS;
        return mtime - durationMs < onAt;
    }

    /** 第 n 次失败后的补传等待(1、2、5、10、30 分钟封顶);只在宿主进程活着时由 Handler 排程,不轮询目录。 */
    public static long retryDelay(int failures) {
        return RETRY_MS[Math.max(0, Math.min(failures - 1, RETRY_MS.length - 1))];
    }
}
