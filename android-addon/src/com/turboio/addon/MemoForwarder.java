package com.turboio.addon;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;

/** 眼镜语音备忘自动进 Obsidian 随记(bill 分支 2026-10-22)。
 *  官方 App 录完约 1 分钟把录音同步成 `app_flutter/user_<uid>/audio/recordSync/<id>.opus`(私有目录,只有宿主进程能读);
 *  本类在宿主进程里用 FileObserver 盯这个目录(事件驱动,不轮询),文件大小稳定 3 s 后经 Tailscale POST 到眼镜网关
 *  `/v1/memo` → suiji-bot 转写写进当日随记。按录音 id 去重;失败留在待传队列,下一个新文件 / 宿主界面重建 / 退避计时器到点时补传。
 *  第一次开启时把已有录音全部记为已处理(不补传旧录音,2026-10-22 Bill 定)。全部状态在主线程改,上传在单独线程串行。 */
final class MemoForwarder {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static SharedPreferences prefs;
    private static final List<FileObserver> observers = new ArrayList<>();
    private static final Map<String, File> pending = new LinkedHashMap<>();
    private static final Set<String> checking = new HashSet<>();
    private static boolean uploading;
    private static final Map<String, Long> retryAt = new HashMap<>();   // 每条录音自己的补传时间(elapsedRealtime)
    private static final Map<String, Integer> failures = new HashMap<>();
    private static String note = "未开始";
    private static final Runnable retry = MemoForwarder::drain;
    private static final String TAG = "TurboIOMemo";
    /** r33 兜底:FileObserver 之外每 RESCAN_MS 列一次目录(只 listFiles,不联网;深度休眠时 Handler 不会唤醒设备)。
     *  r32 实机两条录音都没触发上传,不确定是没同步还是没收到 inotify,两条路都留。 */
    private static final long RESCAN_MS = 120_000;
    private static final Runnable rescan = new Runnable(){public void run(){if(!enabled())return;catchUp();MAIN.postDelayed(this,RESCAN_MS);}};

    static void init(Context c) {
        if (app == null) { app = c.getApplicationContext(); prefs = app.getSharedPreferences("turboio_memo", 0); }
        if (!enabled()) return;
        if (observers.isEmpty()) watch();
        catchUp();  // install() 每次宿主 Activity 重建都会来:顺便补扫一次(只列一个目录,很轻)
        MAIN.removeCallbacks(rescan); MAIN.postDelayed(rescan, RESCAN_MS);
    }
    static boolean enabled() { return prefs != null && prefs.getBoolean("enabled", true); }
    static void enabled(boolean on) {
        prefs.edit().putBoolean("enabled", on).apply();
        if (on) init(app); else { for (FileObserver o : observers) o.stopWatching(); observers.clear(); pending.clear(); retryAt.clear(); failures.clear(); MAIN.removeCallbacks(retry); note = "已关闭"; }
    }
    static String status() {
        if (!enabled()) return "语音备忘自动进随记：已关闭";
        return "语音备忘自动进随记：" + note + (pending.isEmpty() ? "" : " · 待传 " + pending.size()) + " · 已处理 " + done().size() + " 条";
    }

    /** 所有账号目录下的 recordSync(切换账号会有多个 user_<uid>)。 */
    private static List<File> dirs() {
        List<File> out = new ArrayList<>();
        try {
            File flutter = new File(new File(app.getApplicationInfo().dataDir).getCanonicalFile(), "app_flutter");
            File[] users = flutter.listFiles((d, n) -> n.startsWith("user_"));
            if (users != null) for (File u : users) { File r = new File(u, "audio/recordSync"); if (r.isDirectory()) out.add(r); }
        } catch (IOException ignored) {}
        return out;
    }
    private static Set<String> done() { return new HashSet<>(prefs.getStringSet("done", Collections.emptySet())); }
    private static void markDone(String id) { Set<String> d = done(); d.add(id); prefs.edit().putStringSet("done", d).apply(); }

    private static void watch() {
        List<File> ds = dirs();
        if (ds.isEmpty()) { note = "还没有录音目录（录一条后重开 App 生效）"; return; }
        if (!prefs.getBoolean("baseline2", false)) {  // 第一次开启:旧录音不补传。r33 起只把 BASELINE_AGE 以前的算旧——r32 装完马上录的那条被整体基线吞掉了
            Set<String> d = new HashSet<>(); long cut = System.currentTimeMillis() - MemoPolicy.BASELINE_AGE_MS; int n = 0;  // 重建而非并入:r32 从未上传过,旧 done 全是整体基线
            for (File dir : ds) { File[] fs = dir.listFiles(); if (fs != null) for (File f : fs) { String id = MemoPolicy.memoId(f.getName()); if (id != null && f.lastModified() < cut) { d.add(id); n++; } } }
            prefs.edit().putStringSet("done", d).putBoolean("baseline2", true).apply();
            Log.i(TAG, "baseline: " + n + " old memos marked done");
        }
        for (File dir : ds) {
            FileObserver o = new FileObserver(dir, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                @Override public void onEvent(int event, String path) { Log.i(TAG, "event " + event + " " + path); if (path != null) MAIN.post(() -> consider(new File(dir, path))); }
            };
            o.startWatching(); observers.add(o);
        }
        note = "正在监听";
        Log.i(TAG, "watching " + ds);
    }
    private static void catchUp() {
        Set<String> d = done();
        for (File dir : dirs()) { File[] fs = dir.listFiles(); if (fs != null) for (File f : fs) { String id = MemoPolicy.memoId(f.getName()); if (id != null && !d.contains(id)) consider(f); } }
        drain();
    }

    /** 新文件:等大小稳定 STABLE_MS 再入队(同步过程中可能多次写入)。 */
    private static void consider(File f) {
        String id = MemoPolicy.memoId(f.getName());
        if (id == null || !enabled() || done().contains(id) || pending.containsKey(id) || !checking.add(id)) return;
        final long size = f.length();
        Log.i(TAG, "consider " + f.getName() + " " + size + " B");
        MAIN.postDelayed(() -> {
            checking.remove(id);
            if (!f.isFile()) return;
            if (f.length() != size || size == 0) { consider(f); return; }
            Log.i(TAG, "queued " + f.getName()); pending.put(id, f); drain();
        }, MemoPolicy.STABLE_MS);
    }

    private static void drain() {
        if (uploading || pending.isEmpty() || !enabled()) return;
        long now = SystemClock.elapsedRealtime();
        final String id = MemoPolicy.next(new ArrayList<>(pending.keySet()), retryAt, now);
        if (id == null) {  // 全在等补传:按最早那条排一次(catchUp 每 2 min 也会来,不再绕过退避)
            MAIN.removeCallbacks(retry); MAIN.postDelayed(retry, Math.max(1000, Collections.min(retryAt.values()) - now)); return;
        }
        final File f = pending.get(id);
        final String url, key;
        try {
            url = WatchController.origin() + "/v1/memo?memo_id=" + id + "&filename=" + URLEncoder.encode(f.getName(), "UTF-8") + "&ts=" + f.lastModified();
            key = SecretStore.get(app);
        } catch (Exception e) { note = "未配置自有模型地址与 Key，暂不上传"; Log.w(TAG, "no endpoint/key: " + e); return; }
        uploading = true; note = "上传中…";
        Thread t = new Thread(() -> {
            int code = -1; String err = "";
            HttpURLConnection http = null;
            try {
                if (f.length() > MemoPolicy.MAX_BYTES) code = 413;
                else {
                    byte[] body = read(f);
                    http = (HttpURLConnection) new URL(url).openConnection();
                    http.setConnectTimeout(15000); http.setReadTimeout(660000); http.setInstanceFollowRedirects(false);  // 网关等 suiji 最长 600 s(10 MB 约 14 分钟录音)
                    http.setDoOutput(true); http.setFixedLengthStreamingMode(body.length);
                    http.setRequestProperty("Authorization", "Bearer " + key); http.setRequestProperty("Content-Type", "application/octet-stream");
                    try (OutputStream o = http.getOutputStream()) { o.write(body); }
                    code = http.getResponseCode();
                }
            } catch (Exception e) { err = e.getClass().getSimpleName(); }
            finally { if (http != null) http.disconnect(); }
            final int c = code; final String why = err;
            MAIN.post(() -> finished(id, c, why));
        }, "TurboIO-memo");
        t.setDaemon(true); t.start();
    }
    private static void finished(String id, int code, String why) {
        uploading = false;
        MemoPolicy.Outcome o = MemoPolicy.outcome(code);
        Log.i(TAG, "upload " + id + " -> " + code + " " + why + " " + o);
        if (o == MemoPolicy.Outcome.RETRY) {
            int n = failures.containsKey(id) ? failures.get(id) + 1 : 1; failures.put(id, n);
            long d = MemoPolicy.retryDelay(n);
            retryAt.put(id, SystemClock.elapsedRealtime() + d);
            File f = pending.remove(id); if (f != null) pending.put(id, f);  // 挪到队尾,后面的录音先传
            note = "上传失败（" + (code > 0 ? "HTTP " + code : why) + "），" + d / 60000 + " 分钟后补传";
            drain(); return;
        }
        pending.remove(id); retryAt.remove(id); failures.remove(id); markDone(id);
        note = o == MemoPolicy.Outcome.DONE ? "最近一条已记入随记" : "最近一条被跳过（HTTP " + code + "）";
        drain();
    }
    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); return out.toByteArray();
        }
    }
}
