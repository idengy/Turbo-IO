package com.turboio.addon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Read-only candidate browser. Never edits official recording databases. */
final class RecordingExports {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static void show(Activity activity, boolean text) {
        Toast.makeText(activity,"正在查找本机已保存文件",Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<File> files = new ArrayList<>(); Set<String> seen = new HashSet<>(); int[] budget = {30000};
            // bill 分支(2026-10-21):1.0.6 在 files/ 与 app_flutter/ 里扫不到录音(官方代码里录音是 .opus、目录名 audio/recordSync|recordTemp),
            // 改为扫整个私有数据目录(含内部 cache)与外部 Android/data/<包名>,列表显示相对路径,用来定位录音实际存放处
            File data = new File(activity.getApplicationInfo().dataDir), external = activity.getExternalFilesDir(null);
            File externalRoot = external == null ? null : external.getParentFile();
            try {
                for(File root : new File[]{data, externalRoot})
                    if(root != null) scan(root.getCanonicalFile(), 0, text, files, seen, budget);  // r31:先规范化,否则 scan 的符号链接检查可能挡掉整个根
            } catch (IOException ignored) {}
            files.sort((a,b)->Long.compare(b.lastModified(),a.lastModified()));
            MAIN.post(() -> {
                if(activity.isFinishing()) return;
                if(files.isEmpty()) { new AlertDialog.Builder(activity).setTitle("暂未发现可导出文件")
                    .setMessage("请先在官方 App 保存或下载录音。此处仅查本机文件，不代表云端没有记录；数据库内的转写还需单独适配。").setPositiveButton("知道了",null).show(); return; }
                SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm",Locale.ROOT);
                String[] rows = new String[files.size()];
                for(int i=0;i<rows.length;i++) { File file=files.get(i); rows[i]=file.getName()+"\n"+relative(file.getParentFile(),data,externalRoot)+" · "+format.format(new Date(file.lastModified()))+" · "+file.length()/1024+" KB"; }
                new AlertDialog.Builder(activity).setTitle(text?"本地文字 / Markdown 文件":"本地音频候选（录音 / 智记）")
                    .setItems(rows,(d,which)->new AlertDialog.Builder(activity).setTitle("分享所选文件？")
                        .setMessage("仅导出本机副本，不删除原件。请确认这是你要分享的记录。")
                        .setNegativeButton("取消",null).setPositiveButton("打开系统分享",(d2,w)->share(activity,files.get(which),text?"text/plain":"audio/*")).show())
                    .setNegativeButton("返回",null).show();
            });
        },"TurboIO-file-list").start();
    }
    private static void scan(File node,int depth,boolean text,List<File> out,Set<String> seen,int[] budget) throws IOException {
        if(node==null || depth>12 || budget[0]--<=0 || out.size()>=300 || !node.exists()) return;
        if(!node.getAbsolutePath().equals(node.getCanonicalPath())) return; // no symlinks
        if(!seen.add(node.getCanonicalPath())) return;
        if(node.isDirectory()) {
            String name=node.getName(); if(name.equals("flutter_assets")||name.equals("logs")||name.equals("databases")||name.equals("code_cache")||name.equals("app_webview")||name.equals("lib")||name.equals("shared_prefs")) return;
            File[] children=node.listFiles(); if(children!=null) for(File child:children) scan(child,depth+1,text,out,seen,budget);
        } else if(node.isFile() && node.length()>0) {
            String name=node.getName().toLowerCase(Locale.ROOT), path=node.getPath().toLowerCase(Locale.ROOT);
            boolean audio=name.matches(".*\\.(m4a|wav|aac|ogg|opus|mp3|flac)$");
            boolean document=name.endsWith(".md") || (name.endsWith(".txt") && (path.contains("record")||path.contains("transcript")||path.contains("lifelog")||path.contains("always")));
            if((text?document:audio) && !name.startsWith("turboio-")) out.add(node);  // 跳过本页分享时自己拷到 cache/share_plus 的副本
        }
    }
    /** bill 分支临时诊断(2026-10-21,定位 1.0.6 语音备忘存放处,找到后删):列出私有 dataDir 与外部 Android/data/<包> 里
     *  最近 1 小时修改过的全部文件(不看扩展名,含 databases),只显示相对路径/大小/时间,不读内容、不提供分享。 */
    static void recent(Activity activity) {
        Toast.makeText(activity,"正在列出最近 1 小时变动的文件",Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File data = new File(activity.getApplicationInfo().dataDir), external = activity.getExternalFilesDir(null);
            File externalRoot = external == null ? null : external.getParentFile();
            long since = System.currentTimeMillis() - 3600_000L; List<File> files = new ArrayList<>();
            StringBuilder roots = new StringBuilder();
            // r31:每个根单独预算并报告遍历情况(r30 实机私有目录一个文件都没列出,疑根被符号链接检查挡掉或预算耗尽)
            for(File root : new File[]{data, externalRoot}) {
                if(root == null) continue; int[] budget = {80000}; int before = files.size(); String canon;
                try { canon = root.getCanonicalPath(); } catch(IOException e) { canon = "?" ; }
                File[] top = new File(canon).listFiles();
                walk(new File(canon), canon, 0, since, files, budget);
                roots.append("\n根 ").append(root.getAbsolutePath()).append(canon.equals(root.getAbsolutePath()) ? "" : " → " + canon)
                     .append(" · 可列目录 ").append(top == null ? "否" : top.length + " 项").append(" · 遍历 ").append(80000 - budget[0]).append(" 节点")
                     .append(budget[0] <= 0 ? "(预算用尽)" : "").append(" · 命中 ").append(files.size() - before);
            }
            files.sort((a,b)->Long.compare(b.lastModified(),a.lastModified()));
            SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss",Locale.ROOT);
            StringBuilder b = new StringBuilder(files.isEmpty() ? "最近 1 小时没有文件变动" : "共 " + files.size() + " 个(新的在上,最多 150 个)").append(roots).append('\n');
            for(int i=0;i<Math.min(150,files.size());i++){File f=files.get(i);b.append('\n').append(format.format(new Date(f.lastModified()))).append("  ").append(f.length()/1024).append(" KB  ").append(relative(f.getParentFile(),data,externalRoot)).append('/').append(f.getName());}
            final String text = b.toString();
            MAIN.post(() -> { if(activity.isFinishing()) return;
                android.widget.TextView t = new android.widget.TextView(activity); t.setText(text); t.setTextIsSelectable(true); t.setTextSize(12); t.setPadding(32,16,32,16);
                android.widget.ScrollView sv = new android.widget.ScrollView(activity); sv.addView(t);
                new AlertDialog.Builder(activity).setTitle("最近 1 小时变动的文件").setView(sv)
                    .setNeutralButton("复制", (d,w) -> { ((android.content.ClipboardManager)activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Turbo IO 文件变动", text)); Toast.makeText(activity,"已复制",Toast.LENGTH_SHORT).show(); })
                    .setPositiveButton("关闭", null).show(); });
        },"TurboIO-recent-files").start();
    }
    private static void walk(File node,String root,int depth,long since,List<File> out,int[] budget){
        if(node==null||depth>14||budget[0]--<=0||out.size()>=2000)return;
        try{if(!node.getCanonicalPath().startsWith(root))return;}catch(IOException e){return;}  // 只防跳出根的符号链接,根本身先规范化
        if(node.isDirectory()){String n=node.getName();if(n.equals("flutter_assets")||n.equals("code_cache")||n.equals("lib")||n.equals("caption_model"))return;File[] c=node.listFiles();if(c!=null)for(File x:c)walk(x,root,depth+1,since,out,budget);}
        else if(node.isFile()&&node.lastModified()>=since)out.add(node);
    }
    /** 所在目录相对私有数据目录(data/…)或外部目录(ext/…)的路径,列表里定位录音用。 */
    private static String relative(File dir,File data,File external){
        String d=dir==null?"":dir.getAbsolutePath();
        if(data!=null&&d.startsWith(data.getAbsolutePath()))return "data"+d.substring(data.getAbsolutePath().length());
        if(external!=null&&d.startsWith(external.getAbsolutePath()))return "ext"+d.substring(external.getAbsolutePath().length());
        return d;
    }
    static void share(Activity activity,File original,String mime) {
        new Thread(() -> {
            try {
                if(!original.isFile() || original.length()>512L*1024*1024) throw new IOException();
                File folder=new File(activity.getCacheDir(),"share_plus");
                if(!folder.isDirectory() && !folder.mkdirs()) throw new IOException();
                String name="TurboIO-"+UUID.randomUUID().toString().substring(0,8)+"-"+original.getName();
                File copy=new File(folder,name);
                try(InputStream in=new FileInputStream(original); OutputStream out=new FileOutputStream(copy)) {
                    byte[] bytes=new byte[65536]; int n; while((n=in.read(bytes))!=-1) out.write(bytes,0,n);
                }
                // Verified 1.0.4 manifest: cache root is cache/share_plus/.
                Uri uri=new Uri.Builder().scheme("content").authority(activity.getPackageName()+".flutter.share_provider").appendPath("cache").appendPath(name).build();
                MAIN.post(()->{try {
                    Intent intent=new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setClipData(ClipData.newRawUri("Turbo IO export",uri));
                    activity.startActivity(Intent.createChooser(intent,"分享文件副本"));
                } catch(Exception ignored) {Toast.makeText(activity,"分享失败，原件未改动",Toast.LENGTH_LONG).show();}});
            } catch(Exception ignored) { MAIN.post(()->Toast.makeText(activity,"文件不可读或超过 512 MB，原件未改动",Toast.LENGTH_LONG).show()); }
        },"TurboIO-export").start();
    }
}
