import com.turboio.addon.WatchSession;
import com.turboio.addon.WatchSession.Act;
import com.turboio.addon.WatchStream;
import com.turboio.addon.WatchVoice;
import com.turboio.addon.WatchVoice.Command;
/** 盯盘纯逻辑:状态机迁移、重连退避、SSE 累加与字段提取、语音命令匹配。 */
public final class WatchTest {
    private static int checks;
    private static void check(boolean v) { if (!v) throw new AssertionError("check " + checks); checks++; }
    public static void main(String[] a) {
        // 状态机:正常运行
        check(WatchSession.onTick(1000, 0, "starting", false, 0) == Act.NONE);
        check(WatchSession.onTick(WatchSession.RENEW_AT - 1, 0, "ready", false, 0) == Act.NONE);
        check(WatchSession.onTick(WatchSession.RENEW_AT, 0, "ready", false, 0) == Act.RENEW);
        // 续期中:关闭被确认回 idle → 等 1.5 s 再重开
        check(WatchSession.onTick(100, 0, "stopping", true, 0) == Act.NONE);
        check(WatchSession.onTick(1000, 0, "idle", true, 0) == Act.NONE);
        check(WatchSession.onTick(1500, 0, "idle", true, 0) == Act.REOPEN);
        check(WatchSession.onTick(WatchSession.REOPEN_TIMEOUT, 0, "stopping", true, 0) == Act.STOP);
        check(WatchSession.onTick(500, 0, "uncertain", true, 0) == Act.STOP);
        // 非续期:会话被外部结束(眼镜按键/type4/SDK 超时)→ 停,不重开
        check(WatchSession.onTick(5000, 0, "stopping", false, 0) == Act.STOP);
        check(WatchSession.onTick(5000, 0, "uncertain", false, 0) == Act.STOP);
        check(WatchSession.onTick(5000, 0, "idle", false, 0) == Act.STOP);
        check(WatchSession.stopReason("stopping", false).contains("眼镜已退出"));
        check(WatchSession.stopReason("uncertain", true).contains("续期失败"));
        // 退避
        check(WatchSession.backoff(0) == 1000 && WatchSession.backoff(1) == 1000 && WatchSession.backoff(2) == 2000);
        check(WatchSession.backoff(5) == 16000 && WatchSession.backoff(6) == 30000 && WatchSession.backoff(40) == 30000);
        check(!WatchSession.stale(1000, 0) && !WatchSession.stale(1000, 500) && WatchSession.stale(WatchSession.STALE + 10, 10));
        // SSE 累加
        WatchStream s = new WatchStream();
        check(s.accept(": hb") == null && s.accept("") == null);
        check(s.accept("data: {\"type\":\"quote\",\"text\":\"a\\nb\"}") == null);
        String ev = s.accept("");
        check(ev != null && ev.startsWith("{\"type\":\"quote\""));
        check(s.accept("event: x") == null && s.accept("data:first") == null && s.accept("data: second") == null);
        check("first\nsecond".equals(s.accept("")));
        check(s.accept("") == null);
        // 字段提取与事件语义
        String q = "{\"type\": \"quote\", \"text\": \"10:31:05\\n螺纹 3063 -0.55\\n铁矿 677.5 -0.73\", \"asof\": \"2026-10-20 10:31:05\", \"rows\": [{\"name\": \"螺纹钢\", \"last\": 3063.0}]}";
        check("quote".equals(WatchStream.stringField(q, "type")));
        check("10:31:05\n螺纹 3063 -0.55\n铁矿 677.5 -0.73".equals(WatchStream.text(q)));
        check(WatchStream.stringField(q, "rows") == null && WatchStream.stringField(q, "nope") == null);
        check("A\"B\\C中".equals(WatchStream.stringField("{\"text\":\"A\\\"B\\\\C\\u4e2d\"}", "text")));
        check(WatchStream.text("{\"type\":\"other\"}") == null);
        boolean threw = false; try { WatchStream.text("{\"type\":\"error\",\"message\":\"行情源不可用\"}"); } catch (IllegalStateException e) { threw = e.getMessage().equals("行情源不可用"); }
        check(threw);
        check("quote".equals(WatchStream.stringField("{\"name\":\"type\",\"type\":\"quote\"}", "type")));  // 值里出现 key 字样不误判
        // 语音命令
        check(WatchVoice.match("开始盯盘") == Command.START && WatchVoice.match("开始盯盘。") == Command.START && WatchVoice.match(" 打开 盯盘！") == Command.START);
        check(WatchVoice.match("停止盯盘") == Command.STOP && WatchVoice.match("关掉盯盘") == Command.STOP);
        check(WatchVoice.match("帮我分析一下盯盘策略") == Command.NONE && WatchVoice.match("盯盘") == Command.NONE && WatchVoice.match("") == Command.NONE && WatchVoice.match(null) == Command.NONE);
        check(WatchVoice.match("开始盯盘螺纹钢") == Command.NONE);
        System.out.println("PASS " + checks + " watch checks");
    }
}
