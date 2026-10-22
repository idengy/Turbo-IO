package com.turboio.addon;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** 首页「导出录音转写 ›」(bill 分支 r36,2026-10-22)。官方 App「分享转写」只给 `https://share.rayneo.cn/<短码>` 链接;
 *  本页把链接交给 Mac 网关 `/v1/transcript`(无头 Chrome 渲染分享页 → md),存到 `files/turboio_android/transcripts/`,
 *  再经系统分享发到微信 / 飞书。r35 的「复制官方 Isar 库」诊断已删(库里只有会话列表,没有转写正文)。 */
final class TranscriptExports {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern LINK = Pattern.compile("https://share\\.rayneo\\.cn/[A-Za-z0-9]{4,32}");
    private static final int KEEP = 20;

    static void show(Activity a) {
        EditorialUI.Screen s = new EditorialUI.Screen(a, "导出录音转写");
        LinearLayout b = s.body;
        b.addView(EditorialUI.text(a, "在官方 App 录音详情里点「分享转写」→ 复制链接，回到这里点「生成 md」。转写由 Mac 网关打开分享页取回、排成 Markdown，生成后可分享到微信或飞书。", 14, EditorialUI.MUTED));
        EditText link = new EditText(a);
        link.setHint("https://share.rayneo.cn/…");
        link.setTextColor(EditorialUI.INK); link.setHintTextColor(EditorialUI.MUTED);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); link.setSingleLine();
        b.addView(link);
        TextView state = EditorialUI.text(a, "", 14, EditorialUI.LIME);
        LinearLayout result = EditorialUI.column(a), recent = EditorialUI.column(a);
        final Runnable[] list = new Runnable[1];
        EditorialUI.button(a, b, "粘贴链接", false, () -> { String u = clip(a); if (u != null) link.setText(u); else state.setText("剪贴板里没有官方分享转写链接"); });
        final boolean[] busy = {false};
        EditorialUI.button(a, b, "生成 md", true, () -> {
            if (busy[0]) return;
            Matcher m = LINK.matcher(link.getText().toString());
            if (!m.find()) { state.setText("请先粘贴官方分享转写链接（https://share.rayneo.cn/…）"); return; }
            busy[0] = true; state.setText("正在生成…（短录音约 5 秒，长会议可能 1 分钟）"); result.removeAllViews();
            generate(a, m.group(), (file, title, slices, failure) -> {
                busy[0] = false;
                if (a.isFinishing()) return;
                if (failure != null) { state.setText(failure); return; }
                state.setText("已生成「" + title + "」，共 " + slices + " 段");
                EditorialUI.button(a, result, "分享 md 文件到微信 / 飞书", true, () -> RecordingExports.share(a, file, "text/markdown"));
                result.addView(EditorialUI.text(a, preview(file), 13, EditorialUI.INK));
                list[0].run();
            });
        });
        b.addView(state); b.addView(result);
        b.addView(EditorialUI.text(a, "最近导出（点一下再次分享，最多保留 " + KEEP + " 个）", 14, EditorialUI.MUTED));
        b.addView(recent);
        list[0] = () -> {
            recent.removeAllViews();
            for (File f : files(a)) EditorialUI.button(a, recent, f.getName(), false, () -> RecordingExports.share(a, f, "text/markdown"));
        };
        list[0].run();
        MAIN.postDelayed(() -> { String u = clip(a); if (u != null && link.getText().length() == 0) link.setText(u); }, 300);  // 窗口拿到焦点后才读得到剪贴板
    }

    /** 剪贴板里的官方分享链接;没有返回 null。分享文案里可能夹着标题等文字,只取链接。 */
    private static String clip(Activity a) {
        try {
            ClipData c = ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
            if (c == null || c.getItemCount() == 0) return null;
            CharSequence t = c.getItemAt(0).coerceToText(a);
            Matcher m = LINK.matcher(t == null ? "" : t);
            return m.find() ? m.group() : null;
        } catch (Exception e) { return null; }
    }

    interface Done { void run(File file, String title, int slices, String failure); }

    private static void generate(Activity a, String link, Done done) {
        final String url, key;
        try { url = WatchController.origin() + "/v1/transcript?url=" + URLEncoder.encode(link, "UTF-8"); key = SecretStore.get(a); }
        catch (Exception e) { done.run(null, null, 0, "请先在「模型与对话」配置自有模型地址与 Key"); return; }
        Thread t = new Thread(() -> {
            File out = null; String title = null, failure = null; int slices = 0; HttpURLConnection http = null;
            try {
                http = (HttpURLConnection) new URL(url).openConnection();
                http.setConnectTimeout(15000); http.setReadTimeout(150000); http.setInstanceFollowRedirects(false);  // 网关首轮 15 s、慢页再试 40 s 预算
                http.setRequestProperty("Authorization", "Bearer " + key);
                int code = http.getResponseCode();
                String body = read(code < 400 ? http.getInputStream() : http.getErrorStream());
                JSONObject o = new JSONObject(body.isEmpty() ? "{}" : body);
                if (code != 200) {
                    JSONObject err = o.optJSONObject("error");
                    throw new IOException(err != null ? err.optString("message", "HTTP " + code) : "HTTP " + code);
                }
                title = o.optString("title", "录音转写"); slices = o.optInt("slices");
                out = save(a, o.getString("filename"), o.getString("markdown"));
            } catch (Exception e) {
                failure = "生成失败：" + (e instanceof IOException && e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } finally { if (http != null) http.disconnect(); }
            final File f = out; final String tt = title, fail = failure; final int n = slices;
            MAIN.post(() -> done.run(f, tt, n, fail));
        }, "TurboIO-transcript");
        t.setDaemon(true); t.start();
    }

    private static File dir(Context c) { return new File(new File(c.getFilesDir(), "turboio_android"), "transcripts"); }

    /** 存 md;同名覆盖(同一条录音重导出);超过 KEEP 个删最旧的。 */
    private static File save(Activity a, String name, String md) throws IOException {
        File d = dir(a);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("建目录失败");
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", " ").trim();
        if (safe.isEmpty() || !safe.endsWith(".md")) safe = "录音转写.md";
        File f = new File(d, safe);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(md); }
        List<File> all = files(a);
        for (int i = KEEP; i < all.size(); i++) all.get(i).delete();
        return f;
    }

    /** 已导出的 md,最新的在前。 */
    private static List<File> files(Context c) {
        File[] fs = dir(c).listFiles((x, n) -> n.endsWith(".md"));
        List<File> out = fs == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(fs));
        out.sort((x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        return out;
    }

    private static String preview(File f) {
        if (f == null) return "";
        String s = ChatLog.read(f);
        return s.length() > 1500 ? s.substring(0, 1500) + "\n……" : s;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536]; int n;
            while ((n = i.read(b)) > 0) { out.write(b, 0, n); if (out.size() > 8 * 1024 * 1024) throw new IOException("响应过大"); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
