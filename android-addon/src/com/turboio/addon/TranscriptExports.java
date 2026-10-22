package com.turboio.addon;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** 首页「导出录音转写 ›」(bill 分支 r35,2026-10-21)。
 *  官方转写(云端任务结果)缓存在官方 isar_plus 库 `venus_app_db_user_<uid>.isar`(MDBX 二进制,不是 SQLite),格式未知。
 *  r35 先做第一步:把库只读复制到 `Android/data/<包>/files/turboio-isar/`,adb 拉回 Mac 分析结构;r36 换成真正的导出,本段诊断代码随之删除。 */
final class TranscriptExports {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void show(Activity a) {
        EditorialUI.confirm(a, "导出录音转写（第一步）",
            "这一版先复制官方录音数据库的一份副本到本机 Android/data 目录，用来分析官方转写的存储格式，下一版才能直接导出文字。\n\n原数据库只读、不改动；副本只在本机，不上传。\n\n请先在官方 App 里确认至少两条录音已经有转写，再点确认。",
            () -> copy(a));
    }

    private static void copy(Activity a) {
        Toast.makeText(a, "正在复制官方数据库副本", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                File data = new File(a.getApplicationInfo().dataDir).getCanonicalFile(), ext = a.getExternalFilesDir(null);
                if (ext == null) throw new IOException("外部目录不可用");
                List<File> found = new ArrayList<>();
                find(data, 0, found);
                if (found.isEmpty()) throw new IOException("没找到 venus_app_db_*.isar");
                File out = new File(ext, "turboio-isar");
                if (!out.isDirectory() && !out.mkdirs()) throw new IOException("建目录失败");
                long total = 0; StringBuilder names = new StringBuilder();
                for (File f : found) {
                    try (InputStream in = new FileInputStream(f); OutputStream o = new FileOutputStream(new File(out, f.getName()))) {
                        byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) { o.write(b, 0, n); total += n; }
                    }
                    names.append("\n").append(f.getName()).append("  ").append(f.length() / 1024).append(" KB");
                }
                msg = "已复制 " + found.size() + " 个文件（共 " + total / 1024 + " KB）到\n" + out.getAbsolutePath() + names + "\n\n原库未改动。请告诉 Claude 已复制好。";
            } catch (Exception e) { msg = "复制失败：" + e.getMessage(); }
            final String m = msg;
            MAIN.post(() -> { if (!a.isFinishing()) EditorialUI.notice(a, m); });
        }, "TurboIO-isar-copy").start();
    }

    /** 私有数据目录下找官方 Isar 库(只要 .isar 本体,锁文件不要),深度 ≤ 4,跳过 flutter 资源与模型目录。 */
    private static void find(File dir, int depth, List<File> out) {
        if (depth > 4) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (f.isDirectory()) { if (!n.equals("flutter_assets") && !n.equals("caption_model") && !n.equals("lib") && !n.equals("code_cache")) find(f, depth + 1, out); }
            else if (n.startsWith("venus_app_db") && n.endsWith(".isar")) out.add(f);
        }
    }
}
