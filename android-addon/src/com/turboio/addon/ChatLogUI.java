package com.turboio.addon;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.List;

/** 首页「问答对话 ›」(bill 分支 r35,2026-10-21):查看与分享最近 24 小时的语音助手问答(`ChatLog`,文件 = 上游 conversations.md)。
 *  读文件与剪裁走 TurboAddon 的 IO 单线程,与 archive() 追加串行,不会读到写了一半的文件。 */
final class ChatLogUI {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void show(Activity a) {
        EditorialUI.Screen s = new EditorialUI.Screen(a, "问答对话");
        LinearLayout b = s.body;
        b.addView(EditorialUI.text(a, "最近 24 小时通过语音助手和自有模型的问答，最新的在上；超过 24 小时的自动删掉。官方助手自己回答的、「开始盯盘」这类本地指令不记。", 14, EditorialUI.MUTED));
        TextView state = EditorialUI.text(a, "读取中…", 14, EditorialUI.LIME);
        b.addView(state);
        File file = TurboAddon.conversations();
        EditorialUI.button(a, b, "分享 md 文件到其他应用", true, () -> {
            if (!file.isFile() || file.length() == 0) { EditorialUI.notice(a, "最近 24 小时没有问答，暂无可分享的文件。"); return; }
            RecordingExports.share(a, file, "text/markdown");
        });
        LinearLayout list = EditorialUI.column(a);
        final Runnable[] load = new Runnable[1];
        EditorialUI.button(a, b, "刷新", false, () -> load[0].run());
        b.addView(list);
        load[0] = () -> TurboAddon.io().execute(() -> {
            String md;
            try { md = ChatLog.pruneFile(file, System.currentTimeMillis()); } catch (Exception e) { md = ChatLog.read(file); }
            List<ChatLog.Entry> es = ChatLog.entries(md);
            MAIN.post(() -> render(a, state, list, es));
        });
        load[0].run();
    }

    private static void render(Activity a, TextView state, LinearLayout list, List<ChatLog.Entry> es) {
        if (a.isFinishing()) return;
        list.removeAllViews();
        state.setText(es.isEmpty() ? "最近 24 小时没有问答" : "最近 24 小时共 " + es.size() + " 轮");
        for (ChatLog.Entry e : es) {
            LinearLayout c = EditorialUI.card(a, list);
            c.addView(EditorialUI.text(a, e.stamp, 12, EditorialUI.MUTED));
            TextView q = EditorialUI.text(a, e.question.isEmpty() ? "(无问题文本)" : e.question, 16, EditorialUI.LIME);
            q.setTypeface(null, Typeface.BOLD);
            c.addView(q);
            String answer = MarkdownText.looksLikeMarkdown(e.answer) ? MarkdownText.convert(e.answer).text.trim() : e.answer;
            TextView t = EditorialUI.text(a, answer, 15, EditorialUI.INK);
            t.setTextIsSelectable(true);
            c.addView(t);
        }
    }
}
