package com.turboio.addon;
import android.app.*;import android.os.*;import android.widget.*;
/** 背单词页(bill 分支 2026-10-20):有道单词本经 Mac 网关排版后在眼镜文字面板循环显示「单词 / 音标 / 释义」,不出声。
 *  词库、顺序、排版、节奏都在网关(custom/glasses-gateway/words.py);这里只有开始/停止、每帧秒数、随机开关(宽度探针入口 2026-10-21 r23 移除,网关 probe=1 仍在)。复用 WatchController 的 SSE→面板壳。 */
final class WordsUI {
 private static final int[] SECONDS={4,6,8,10,15};
 static void show(Activity a){EditorialUI.Screen s=new EditorialUI.Screen(a,"背单词");LinearLayout b=s.body;
  b.addView(EditorialUI.text(a,"眼镜文字面板循环显示有道单词本：第 1 行单词与音标，其余行释义；释义长的词分成多帧。词库在 Mac 上用 youdao_sync.py 同步，网关记住看到哪个词，下次开始先重放最后那个词。眼镜上转一下旋钮（哪个方向都行）= 回到上一个词并停 15 秒，连转继续往前退，停手后接着放新词；长按旋钮 = 停止。面板会占满眼镜画面，期间音乐/阅读/导航不可用；会话每 55 分钟自动续开；退后台继续需在设置里开「后台运行」。",14,EditorialUI.MUTED));
  TextView state=EditorialUI.text(a,"",14,EditorialUI.LIME);b.addView(state);
  EditorialUI.button(a,b,"开始背单词",true,()->run(a,WatchController.startWords(WatchController.wordsSeconds(),WatchController.wordsShuffle())));
  EditorialUI.button(a,b,"停止",false,()->WatchController.stop("用户停止"));
  EditorialUI.button(a,b,"确认眼镜已回首页（解除会话占用）",false,()->EditorialUI.confirm(a,"眼镜已退出文字面板？","只有镜片确实回到首页才点确认；否则下一次开始可能叠加会话。",NavGlasses::confirmIdle));
  LinearLayout card=EditorialUI.card(a,b);card.addView(EditorialUI.text(a,"节奏（下一次开始生效）",15,EditorialUI.INK));
  final TextView[] sec=new TextView[1];
  sec[0]=EditorialUI.button(a,card,label(),false,()->{int cur=WatchController.wordsSeconds(),next=SECONDS[0];for(int i=0;i<SECONDS.length;i++)if(SECONDS[i]==cur){next=SECONDS[(i+1)%SECONDS.length];break;}WatchController.wordsSeconds(next);sec[0].setText(label());});
  EditorialUI.toggle(a,card,"随机顺序（关 = 最近添加的先看）",WatchController.wordsShuffle(),(v,on)->WatchController.wordsShuffle(on));
  Handler h=new Handler();Runnable tick=new Runnable(){public void run(){if(!s.dialog.isShowing())return;state.setText(NavGlasses.connection()+"\n"+WatchController.status()+(WatchController.lastText().isEmpty()?"":"\n— 最近一帧 —\n"+WatchController.lastText()));h.postDelayed(this,500);}};h.post(tick);
  s.dialog.setOnDismissListener(d->h.removeCallbacks(tick));}
 private static String label(){return "每帧 "+WatchController.wordsSeconds()+" 秒（点按切换 4/6/8/10/15）";}
 private static void run(Activity a,String failure){if(failure!=null)EditorialUI.notice(a,failure);}
}
