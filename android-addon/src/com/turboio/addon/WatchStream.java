package com.turboio.addon;

/** SSE 行累加器 + 最小 JSON 字符串字段提取(纯逻辑,不依赖 org.json,方便 javac 单测)。
 *  网关事件:data: {"type":"quote","text":"…","asof":"…"} / {"type":"error","message":"…"};注释行 ": hb" 是心跳。 */
public final class WatchStream {
    private final StringBuilder data = new StringBuilder();
    private boolean has;

    /** 喂一行(不含换行)。空行结束一个事件并返回其 data 载荷;否则返回 null。 */
    public String accept(String line) {
        if (line.isEmpty()) {
            if (!has) return null;
            String s = data.toString(); data.setLength(0); has = false; return s;
        }
        if (line.charAt(0) == ':') return null;
        if (line.startsWith("data:")) {
            String v = line.substring(5);
            if (v.startsWith(" ")) v = v.substring(1);
            if (has) data.append('\n');
            data.append(v); has = true;
        }
        return null;
    }

    /** 取顶层 "key":"string" 的值(处理反斜杠、引号、n r t 与 u 四位十六进制等转义);没有或不是字符串返回 null。只用于网关自己生成的 JSON。 */
    public static String stringField(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        while (i >= 0) {
            int j = i + needle.length();
            while (j < json.length() && json.charAt(j) == ' ') j++;
            if (j < json.length() && json.charAt(j) == ':') {
                j++;
                while (j < json.length() && json.charAt(j) == ' ') j++;
                if (j >= json.length() || json.charAt(j) != '"') return null;
                StringBuilder out = new StringBuilder();
                for (int k = j + 1; k < json.length(); k++) {
                    char c = json.charAt(k);
                    if (c == '"') return out.toString();
                    if (c != '\\') { out.append(c); continue; }
                    if (++k >= json.length()) return null;
                    char e = json.charAt(k);
                    switch (e) {
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'u':
                            if (k + 4 >= json.length()) return null;
                            out.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16)); k += 4; break;
                        default: out.append(e);
                    }
                }
                return null;
            }
            i = json.indexOf(needle, i + 1);
        }
        return null;
    }

    /** quote 事件返回面板文字;error 事件抛 IllegalStateException(message);其他事件返回 null。 */
    public static String text(String json) {
        String type = stringField(json, "type");
        if ("error".equals(type)) throw new IllegalStateException(orDefault(stringField(json, "message"), "行情源错误"));
        if (!"quote".equals(type)) return null;
        return orDefault(stringField(json, "text"), "");
    }

    private static String orDefault(String s, String d) { return s == null ? d : s; }
}
