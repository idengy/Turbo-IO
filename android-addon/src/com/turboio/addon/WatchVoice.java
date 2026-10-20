package com.turboio.addon;

import java.util.*;

/** 眼镜语音本地命令「开始盯盘 / 停止盯盘」「开始背单词 / 停止背单词」(纯逻辑)。去空白与标点后精确匹配;规范化后超过 8 字一律不算,免得劫持「帮我分析一下盯盘策略」。 */
public final class WatchVoice {
    public enum Command { START, STOP, START_WORDS, STOP_WORDS, NONE }
    private static final Set<String> START = new HashSet<>(Arrays.asList("开始盯盘", "盯盘开始", "打开盯盘", "开启盯盘", "进入盯盘", "盯盘模式", "开盯盘", "启动盯盘"));
    private static final Set<String> STOP = new HashSet<>(Arrays.asList("停止盯盘", "关闭盯盘", "盯盘停止", "结束盯盘", "退出盯盘", "关掉盯盘", "关盯盘", "停盯盘"));
    private static final Set<String> START_WORDS = new HashSet<>(Arrays.asList("开始背单词", "背单词", "打开背单词", "开启背单词", "进入背单词", "背单词模式", "单词模式", "开始单词", "打开单词本", "开始背词"));
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList("停止背单词", "关闭背单词", "结束背单词", "退出背单词", "关掉背单词", "不背了", "停止单词", "关闭单词本", "退出单词模式"));

    /** 同音错字 → 正字(成对:错, 正)。2026-10-20 Hermes 日志实录:「开始定盘。」「开始订牌。」 */
    private static final String[] HOMOPHONES = {
        "定盘", "盯盘", "订盘", "盯盘", "顶盘", "盯盘", "钉盘", "盯盘", "丁盘", "盯盘", "叮盘", "盯盘",
        "订牌", "盯盘", "定牌", "盯盘", "盯牌", "盯盘", "顶牌", "盯盘", "钉牌", "盯盘",
        "北单词", "背单词", "倍单词", "背单词", "被单词", "背单词", "备单词", "背单词", "贝单词", "背单词",
    };
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
        // r13(2026-10-20 实机):官方 ASR 把「盯盘」听成「定盘」「订牌」,整句落到模型路径。匹配前把同音错字归一化;表只收 ASR 真会产出的写法。
        for (int i = 0; i < HOMOPHONES.length; i += 2) t = t.replace(HOMOPHONES[i], HOMOPHONES[i + 1]);
        if (START.contains(t)) return Command.START;
        if (STOP.contains(t)) return Command.STOP;
        if (START_WORDS.contains(t)) return Command.START_WORDS;
        if (STOP_WORDS.contains(t)) return Command.STOP_WORDS;
        return Command.NONE;
    }
}
