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

    /** r21(2026-10-21 r20 实机):眼镜长按退出字幕面板时,启动器(业务 15)发 type 24 `glass_preview`,
     *  data 里 app.name=com.rayneo.liteos.aiSubtitle、action=2(开面板时同一条 action=1,回主屏另有 scence=0)。
     *  帧 = protobuf{1 版本,2 类型,3 JSON,4 二进制};data 是转义过的 JSON 字符串,先去转义再匹配。 */
    public static boolean subtitleExit(byte[] frame) {
        String json = launcherJson(frame, 24);
        if (json == null) return false;
        String j = json.replace("\\\"", "\"");
        return j.matches("(?s).*\"cmd\"\\s*:\\s*\"glass_preview\".*")
            && j.matches("(?s).*\"name\"\\s*:\\s*\"com\\.rayneo\\.liteos\\.aiSubtitle\".*")
            && j.matches("(?s).*\"action\"\\s*:\\s*2(?![0-9]).*");
    }
    /** r22(2026-10-21 r21 实机):字幕面板上转旋钮,启动器(业务 15)每格发一条 type 76 `analytics_log`,
     *  extras.action_type=scroll(面板无历史时 action_result=fail);前转后转内容完全相同,分不出方向。 */
    public static boolean wheelScroll(byte[] frame) {
        String json = launcherJson(frame, 76);
        if (json == null) return false;
        String j = json.replace("\\\"", "\"");
        return j.matches("(?s).*\"cmd\"\\s*:\\s*\"analytics_log\".*") && j.matches("(?s).*\"action_type\"\\s*:\\s*\"scroll\".*");
    }
    /** 取业务 15 帧里 type == want 的 JSON;格式不对或类型不符返回 null。 */
    static String launcherJson(byte[] b, long want) {
        if (b == null || b.length > 8192) return null;
        try {
            int[] p = {0}; long type = -1; String json = null;
            while (p[0] < b.length) {
                long k = var(b, p); int tag = (int) (k >>> 3), wire = (int) (k & 7);
                if (wire == 2) { int n = (int) var(b, p); if (n < 0 || n > b.length - p[0]) return null; if (tag == 3) json = new String(b, p[0], n, "UTF-8"); p[0] += n; }
                else if (wire == 0) { long v = var(b, p); if (tag == 2) type = v; }
                else return null;
            }
            return type == want ? json : null;
        } catch (Exception e) { return null; }
    }
    private static long var(byte[] b, int[] p) {
        long n = 0;
        for (int i = 0; i < 10; i++) { if (p[0] >= b.length) throw new IllegalArgumentException(); int c = b[p[0]++] & 255; n |= (long) (c & 127) << (7 * i); if ((c & 128) == 0) return n; }
        throw new IllegalArgumentException();
    }
}
