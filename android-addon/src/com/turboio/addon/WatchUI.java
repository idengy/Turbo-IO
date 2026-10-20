package com.turboio.addon;
import android.app.*;import android.os.*;import android.widget.*;import java.util.*;
/** 期货盯盘页(bill 分支):勾选品种(≤5)、开始/停止、确认眼镜已回首页、诊断探针。关页不停盯盘(后台继续要在设置里开「后台运行」)。 */
final class WatchUI {
 static void show(Activity a){EditorialUI.Screen s=new EditorialUI.Screen(a,"期货盯盘");LinearLayout b=s.body;
  b.addView(EditorialUI.text(a,"眼镜文字面板显示所选品种的最新价与涨跌幅，约 1 秒一帧；面板会占满眼镜画面，期间音乐/阅读/导航不可用。会话每 55 分钟自动续开。退后台继续需在设置里开「后台运行」。",14,EditorialUI.MUTED));
  TextView state=EditorialUI.text(a,"",14,EditorialUI.LIME);b.addView(state);
  EditorialUI.button(a,b,"开始盯盘",true,()->run(a,WatchController.start()));
  EditorialUI.button(a,b,"停止盯盘",false,()->WatchController.stop("用户停止"));
  EditorialUI.button(a,b,"确认眼镜已回首页（解除会话占用）",false,()->EditorialUI.confirm(a,"眼镜已退出文字面板？","只有镜片确实回到首页才点确认；否则下一次开始可能叠加会话。",NavGlasses::confirmIdle));
  // 品种
  LinearLayout card=EditorialUI.card(a,b);TextView head=EditorialUI.text(a,"品种（最多 5 个，按勾选顺序显示）",15,EditorialUI.INK);card.addView(head);
  LinearLayout list=new LinearLayout(a);list.setOrientation(LinearLayout.VERTICAL);card.addView(list);
  Runnable render=()->{list.removeAllViews();List<String[]> cat=WatchController.catalog();List<String> chosen=new ArrayList<>(WatchController.names());
   if(cat.isEmpty()){list.addView(EditorialUI.text(a,"还没有品种表，点下面「刷新品种列表」从 Mac 拉取。",14,EditorialUI.MUTED));return;}
   for(String[] e:cat){final String name=e[0];CheckBox cb=new CheckBox(a);cb.setText(name+(e[1].equals(name)?"":"（"+e[1]+"）"));cb.setTextColor(EditorialUI.INK);cb.setTextSize(15);cb.setChecked(chosen.contains(name));
    cb.setOnCheckedChangeListener((v,on)->{List<String> now=new ArrayList<>(WatchController.names());if(on){if(now.contains(name))return;if(now.size()>=WatchController.MAX_NAMES){cb.setChecked(false);EditorialUI.notice(a,"最多 5 个品种，先取消一个再勾");return;}now.add(name);}else now.remove(name);WatchController.names(now);head.setText("品种（已选 "+now.size()+"/5，按勾选顺序显示）");});
    list.addView(cb);}
   head.setText("品种（已选 "+chosen.size()+"/5，按勾选顺序显示）");};
  render.run();
  EditorialUI.button(a,card,"刷新品种列表",false,()->WatchController.refreshCatalog(f->{if(f!=null)EditorialUI.notice(a,f);else render.run();}));
  // 诊断
  LinearLayout diag=EditorialUI.card(a,b);diag.addView(EditorialUI.text(a,"诊断 · 面板探针（固件或 App 升级后复测）",15,EditorialUI.INK));
  EditorialUI.toggle(a,diag,"字号 1（默认 2，下一次开始生效）",NavGlasses.captionFont==1,(v,on)->NavGlasses.captionFont=on?1:2);
  EditorialUI.button(a,diag,"盯盘测试 · 合成 5 行行情 3 分钟",false,()->run(a,WatchController.startTest()));
  EditorialUI.button(a,diag,"宽度探针 · 5 行递增长度",false,()->run(a,WatchController.startProbe("width")));
  EditorialUI.button(a,diag,"替换/追加探针 · A 帧 4 秒后 B 帧",false,()->run(a,WatchController.startProbe("append")));
  EditorialUI.button(a,diag,"停止探针",false,()->WatchController.stop("用户停止"));
  Handler h=new Handler();Runnable tick=new Runnable(){public void run(){if(!s.dialog.isShowing())return;state.setText(NavGlasses.connection()+"\n"+NavGlasses.status()+"\n"+WatchController.status()+(WatchController.lastText().isEmpty()?"":"\n— 最近一帧 —\n"+WatchController.lastText()));h.postDelayed(this,500);}};h.post(tick);
  s.dialog.setOnDismissListener(d->h.removeCallbacks(tick));}
 private static void run(Activity a,String failure){if(failure!=null)EditorialUI.notice(a,failure);}
}
