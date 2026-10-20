package com.turboio.addon;
import android.app.*;import android.os.*;import android.widget.*;
/** 期货盯盘页(bill 分支)。P0:探针与合成行情;完整模式的品种勾选/开始/停止在后续步骤加。关页不停探针。 */
final class WatchUI {
 static void show(Activity a){EditorialUI.Screen s=new EditorialUI.Screen(a,"期货盯盘（实验）");LinearLayout b=s.body;
  b.addView(EditorialUI.text(a,"P0 探针：先确认眼镜文字面板在当前固件上的行为（换行、每行字数、替换还是叠加、1 秒一帧是否稳定），再接真实行情。面板会占满眼镜画面；结束用「停止」，眼镜没退出就按实体键，然后点「确认眼镜已回首页」解除会话占用。",14,EditorialUI.MUTED));
  TextView state=EditorialUI.text(a,"",14,EditorialUI.LIME);b.addView(state);
  EditorialUI.toggle(a,b,"字号 1（默认 2，下一次开始生效）",NavGlasses.captionFont==1,(v,on)->NavGlasses.captionFont=on?1:2);
  EditorialUI.button(a,b,"盯盘测试 · 合成 5 行行情 3 分钟",true,()->run(a,WatchController.startTest()));
  EditorialUI.button(a,b,"宽度探针 · 5 行递增长度",false,()->run(a,WatchController.startProbe("width")));
  EditorialUI.button(a,b,"替换/追加探针 · A 帧 4 秒后 B 帧",false,()->run(a,WatchController.startProbe("append")));
  EditorialUI.button(a,b,"停止",false,()->WatchController.stop("用户停止"));
  EditorialUI.button(a,b,"确认眼镜已回首页（解除会话占用）",false,()->EditorialUI.confirm(a,"眼镜已退出文字面板？","只有镜片确实回到首页才点确认；否则下一次开始可能叠加会话。",NavGlasses::confirmIdle));
  Handler h=new Handler();Runnable tick=new Runnable(){public void run(){if(!s.dialog.isShowing())return;state.setText(NavGlasses.connection()+"\n"+NavGlasses.status()+"\n"+WatchController.status());h.postDelayed(this,500);}};h.post(tick);
  s.dialog.setOnDismissListener(d->h.removeCallbacks(tick));}
 private static void run(Activity a,String failure){if(failure!=null)EditorialUI.notice(a,failure);}
}
