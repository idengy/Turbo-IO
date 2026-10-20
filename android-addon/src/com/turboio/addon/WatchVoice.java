package com.turboio.addon;

import java.util.*;

/** 眼镜语音本地命令「开始盯盘 / 停止盯盘」(纯逻辑)。去空白与标点后精确匹配;规范化后超过 8 字一律不算,免得劫持「帮我分析一下盯盘策略」。 */
public final class WatchVoice {
    public enum Command { START, STOP, NONE }
    private static final Set<String> START = new HashSet<>(Arrays.asList("开始盯盘", "盯盘开始", "打开盯盘", "开启盯盘", "进入盯盘", "盯盘模式", "开盯盘", "启动盯盘"));
    private static final Set<String> STOP = new HashSet<>(Arrays.asList("停止盯盘", "关闭盯盘", "盯盘停止", "结束盯盘", "退出盯盘", "关掉盯盘", "关盯盘", "停盯盘"));

    public static Command match(String text) {
        if (text == null) return Command.NONE;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || "，。！？、,.!?;；:：\"'“”‘’()（）".indexOf(c) >= 0) continue;
            b.append(c);
        }
        String t = b.toString();
        if (t.isEmpty() || t.length() > 8) return Command.NONE;
        if (START.contains(t)) return Command.START;
        if (STOP.contains(t)) return Command.STOP;
        return Command.NONE;
    }
}
