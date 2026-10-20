package com.turboio.addon;
import android.app.*;import android.os.*;import android.widget.*;import java.util.*;
/** 期货盯盘页(bill 分支):勾选品种(≤5)、开始/停止、确认眼镜已回首页、诊断探针。关页不停盯盘(后台继续要在设置里开「后台运行」)。 */
final class WatchUI {
 static void show(Activity a){EditorialUI.Screen s=new EditorialUI.Screen(a,"期货盯盘");LinearLayout b=s.body;
  b.addView(EditorialUI.text(a,"眼镜文字面板显示所选合约的最新价与涨跌幅，约 1 秒一帧；面板会占满眼镜画面，期间音乐/阅读/导航不可用。会话每 55 分钟自动续开。眼镜上长按实体键会退出面板但手机不会知道，再点一次「开始盯盘」或对眼镜说「开始盯盘」即可重新投上去。退后台继续需在设置里开「后台运行」。",14,EditorialUI.MUTED));
  TextView state=EditorialUI.text(a,"",14,EditorialUI.LIME);b.addView(state);
  EditorialUI.button(a,b,"开始盯盘 / 重新投到眼镜",true,()->run(a,WatchController.start()));
  EditorialUI.button(a,b,"停止盯盘",false,()->WatchController.stop("用户停止"));
  EditorialUI.button(a,b,"确认眼镜已回首页（解除会话占用）",false,()->EditorialUI.confirm(a,"眼镜已退出文字面板？","只有镜片确实回到首页才点确认；否则下一次开始可能叠加会话。",NavGlasses::confirmIdle));
  // 品种:搜索框添加(品种 / 品种+月份),列表可移除,最多 5 个
  LinearLayout card=EditorialUI.card(a,b);TextView head=EditorialUI.text(a,"合约（最多 5 个，按添加顺序显示）",15,EditorialUI.INK);card.addView(head);
  LinearLayout list=new LinearLayout(a);list.setOrientation(LinearLayout.VERTICAL);card.addView(list);
  final Runnable[] render=new Runnable[1];render[0]=()->{list.removeAllViews();List<String> chosen=WatchController.names();head.setText("合约（已选 "+chosen.size()+"/5，按添加顺序显示）");
   if(chosen.isEmpty())list.addView(EditorialUI.text(a,"还没有合约。在下面输入「铁矿 2701」「螺纹钢」这样的名字后点添加；不写月份就是主力连续。",14,EditorialUI.MUTED));
   for(String label:chosen){LinearLayout row=new LinearLayout(a);row.setGravity(android.view.Gravity.CENTER_VERTICAL);TextView t=EditorialUI.text(a,label,16,EditorialUI.INK);row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
    TextView rm=EditorialUI.text(a,"移除",14,EditorialUI.LIME);rm.setPadding(EditorialUI.dp(a,12),EditorialUI.dp(a,8),EditorialUI.dp(a,4),EditorialUI.dp(a,8));rm.setOnClickListener(v->{List<String> now=new ArrayList<>(WatchController.names());now.remove(label);WatchController.names(now);render[0].run();});row.addView(rm);list.addView(row);}};
  render[0].run();
  EditText input=NewsUI.input(a,card,"例：铁矿 2701 / 白糖 701 / 螺纹钢（主连）","");
  EditorialUI.button(a,card,"添加",false,()->{String q=input.getText().toString().trim();if(q.isEmpty())return;List<String> now=new ArrayList<>(WatchController.names());if(now.size()>=WatchController.MAX_NAMES){EditorialUI.notice(a,"最多 5 个，先移除一个");return;}
   WatchController.resolve(q,(label,display,failure)->{if(failure!=null){EditorialUI.notice(a,failure);return;}List<String> cur=new ArrayList<>(WatchController.names());if(cur.contains(label)){EditorialUI.notice(a,label+" 已在列表");return;}if(cur.size()>=WatchController.MAX_NAMES){EditorialUI.notice(a,"最多 5 个，先移除一个");return;}cur.add(label);WatchController.names(cur);input.setText("");render[0].run();});});
  // 诊断
  LinearLayout diag=EditorialUI.card(a,b);diag.addView(EditorialUI.text(a,"诊断 · 面板探针（固件或 App 升级后复测）",15,EditorialUI.INK));
  EditorialUI.toggle(a,diag,"字号 1（默认 2，下一次开始生效）",NavGlasses.captionFont==1,(v,on)->NavGlasses.captionFont=on?1:2);
  EditorialUI.toggle(a,diag,"停止前先发一帧空白清屏（若停止后行情仍留在镜片上再开）",NavGlasses.clearBeforeClose,(v,on)->NavGlasses.clearBeforeClose=on);
  EditorialUI.button(a,diag,"盯盘测试 · 合成 5 行行情 3 分钟",false,()->run(a,WatchController.startTest()));
  EditorialUI.button(a,diag,"宽度探针 · 5 行递增长度",false,()->run(a,WatchController.startProbe("width")));
  EditorialUI.button(a,diag,"替换/追加探针 · A 帧 4 秒后 B 帧",false,()->run(a,WatchController.startProbe("append")));
  EditorialUI.button(a,diag,"停止探针",false,()->WatchController.stop("用户停止"));
  Handler h=new Handler();Runnable tick=new Runnable(){public void run(){if(!s.dialog.isShowing())return;state.setText(NavGlasses.connection()+"\n"+NavGlasses.status()+"\n"+WatchController.status()+(WatchController.lastText().isEmpty()?"":"\n— 最近一帧 —\n"+WatchController.lastText()));h.postDelayed(this,500);}};h.post(tick);
  s.dialog.setOnDismissListener(d->h.removeCallbacks(tick));}
 private static void run(Activity a,String failure){if(failure!=null)EditorialUI.notice(a,failure);}
}
