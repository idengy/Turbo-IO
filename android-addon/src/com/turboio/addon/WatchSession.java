package com.turboio.addon;

/** 盯盘会话状态机(纯逻辑,无 Android 依赖,可直接 javac 单测)。WatchController 每秒拿 NavGlasses 的 phase 来问「现在该做什么」。
 *  续期:ready 满 RENEW_AT 后发 RENEW(NavGlasses.renew() 发 type3 关闭);关闭被 SDK 确认后 NavGlasses 回 idle,
 *  再等 REOPEN_DELAY 发 REOPEN(新 sid);关闭 REOPEN_TIMEOUT 内没回 idle 或落到 uncertain → STOP,交给用户确认。
 *  非续期状态下 phase 离开 ready/starting(眼镜按键、官方语音 type4、SDK 超时)→ STOP,不自动重开。 */
public final class WatchSession {
    public enum Act { NONE, RENEW, REOPEN, STOP }
    public static final long LIFETIME = 3600000, RENEW_AT = 3300000, REOPEN_DELAY = 1500, REOPEN_TIMEOUT = 15000, STALE = 90000, MAX_BACKOFF = 30000;

    /** 重连退避:1,2,4,8,16,30,30… 秒(毫秒)。 */
    public static long backoff(int attempt) {
        if (attempt <= 1) return 1000;
        long b = 1000L << Math.min(attempt - 1, 5);
        return Math.min(b, MAX_BACKOFF);
    }

    public static Act onTick(long now, long sessionStart, String phase, boolean renewing, long closeAt) {
        if (renewing) {
            if ("idle".equals(phase)) return now - closeAt >= REOPEN_DELAY ? Act.REOPEN : Act.NONE;
            if ("uncertain".equals(phase)) return Act.STOP;
            if ("stopping".equals(phase)) return now - closeAt >= REOPEN_TIMEOUT ? Act.STOP : Act.NONE;
            return Act.NONE;
        }
        if ("ready".equals(phase)) return now - sessionStart >= RENEW_AT ? Act.RENEW : Act.NONE;
        if ("starting".equals(phase)) return Act.NONE;
        return Act.STOP;
    }

    public static String stopReason(String phase, boolean renewing) {
        if (renewing) return "续期失败（" + phase + "），请确认眼镜已回首页后再开始";
        if ("stopping".equals(phase) || "uncertain".equals(phase)) return "眼镜已退出显示（按键或官方功能接管），盯盘停止";
        return "显示会话已结束，盯盘停止";
    }

    public static boolean stale(long now, long lastData) { return lastData > 0 && now - lastData >= STALE; }
}
