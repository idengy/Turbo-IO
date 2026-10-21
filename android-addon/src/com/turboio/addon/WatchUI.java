package com.turboio.addon;
import android.app.*;import android.os.*;import android.widget.*;import java.util.*;
/** 期货盯盘页(bill 分支):添加合约(≤12,r10 前 ≤5;超过一屏 = 眼镜滚轮翻 / 轮播)、开始/停止、确认眼镜已回首页(诊断探针与开关入口 2026-10-21 r23 移除,WatchController.startTest/startProbe 保留)。关页不停盯盘(后台继续要在设置里开「后台运行」)。 */
final class WatchUI {
 static void show(Activity a){EditorialUI.Screen s=new EditorialUI.Screen(a,"期货盯盘");LinearLayout b=s.body;
  b.addView(EditorialUI.text(a,"眼镜文字面板显示所选合约的最新价与涨跌幅，约 1 秒一帧；每页 6 行（可调），超过时默认自动轮播翻页。面板会占满眼镜画面，期间音乐/阅读/导航不可用。会话每 55 分钟自动续开。眼镜上长按旋钮退出面板 = 停止（手机随之释放会话，同点「停止」）；想再看就点「开始盯盘」或对眼镜说「开始盯盘」。退后台继续需在设置里开「后台运行」。",14,EditorialUI.MUTED));
  TextView state=EditorialUI.text(a,"",14,EditorialUI.LIME);b.addView(state);
  EditorialUI.button(a,b,"开始盯盘 / 重新投到眼镜",true,()->run(a,WatchController.start()));
  EditorialUI.button(a,b,"停止盯盘",false,()->WatchController.stop("用户停止"));
  EditorialUI.button(a,b,"确认眼镜已回首页（解除会话占用）",false,()->EditorialUI.confirm(a,"眼镜已退出文字面板？","只有镜片确实回到首页才点确认；否则下一次开始可能叠加会话。",NavGlasses::confirmIdle));
  // 品种:搜索框添加(品种 / 品种+月份),列表可移除,最多 MAX_NAMES 个
  final int max=WatchController.MAX_NAMES;
  LinearLayout card=EditorialUI.card(a,b);TextView head=EditorialUI.text(a,"合约（最多 "+max+" 个，按添加顺序显示）",15,EditorialUI.INK);card.addView(head);
  LinearLayout list=new LinearLayout(a);list.setOrientation(LinearLayout.VERTICAL);card.addView(list);
  final Runnable[] render=new Runnable[1];render[0]=()->{list.removeAllViews();List<String> chosen=WatchController.names();head.setText("合约（已选 "+chosen.size()+"/"+max+"，按添加顺序显示）");
   if(chosen.isEmpty())list.addView(EditorialUI.text(a,"还没有合约。在下面输入「铁矿 2701」「螺纹钢」这样的名字后点添加；不写月份就是主力连续。",14,EditorialUI.MUTED));
   for(int idx=0;idx<chosen.size();idx++){final int i=idx;final String label=chosen.get(i);LinearLayout row=new LinearLayout(a);row.setGravity(android.view.Gravity.CENTER_VERTICAL);TextView t=EditorialUI.text(a,(i+1)+". "+label,16,EditorialUI.INK);row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
    // r14:上移 / 下移 调顺序(眼镜按此顺序分页显示)
    TextView up=act(a,"↑",i>0,v->{List<String> now=new ArrayList<>(WatchController.names());if(i>0&&i<now.size()){Collections.swap(now,i,i-1);WatchController.names(now);render[0].run();}});row.addView(up);
    TextView dn=act(a,"↓",i<chosen.size()-1,v->{List<String> now=new ArrayList<>(WatchController.names());if(i>=0&&i<now.size()-1){Collections.swap(now,i,i+1);WatchController.names(now);render[0].run();}});row.addView(dn);
    TextView rm=act(a,"移除",true,v->{List<String> now=new ArrayList<>(WatchController.names());now.remove(label);WatchController.names(now);render[0].run();});row.addView(rm);list.addView(row);}};
  render[0].run();
  EditText input=NewsUI.input(a,card,"例：铁矿 2701 / 白糖 701 / 螺纹钢（主连）；多个用 / 或换行分隔，一次添加","");input.setSingleLine(false);input.setMaxLines(5);input.setVerticalScrollBarEnabled(true);
  EditorialUI.button(a,card,"添加",false,()->{String q=input.getText().toString().trim();if(q.isEmpty())return;
   // r14:一次多个,按 / 、换行、逗号、分号拆开后逐个解析(网关 resolve 一次一个),最后汇总一次提示
   final List<String> items=new ArrayList<>();for(String x:q.split("[/／\\n\\r,，;；、]"))if(!x.trim().isEmpty())items.add(x.trim());
   final List<String> added=new ArrayList<>(),skipped=new ArrayList<>();
   final Runnable[] next=new Runnable[1];next[0]=()->{if(items.isEmpty()){input.setText("");render[0].run();if(items.size()+added.size()+skipped.size()>1||!skipped.isEmpty())EditorialUI.notice(a,"已添加 "+added.size()+" 个"+(added.isEmpty()?"":"："+String.join("、",added))+(skipped.isEmpty()?"":"\n未添加："+String.join("\n",skipped)));return;}
    final String one=items.remove(0);List<String> now=new ArrayList<>(WatchController.names());if(now.size()>=max){skipped.add(one+"（已满 "+max+" 个）");items.clear();next[0].run();return;}
    WatchController.resolve(one,(label,display,failure)->{List<String> cur=new ArrayList<>(WatchController.names());
     if(failure!=null)skipped.add(one+"：没找到");else if(cur.contains(label))skipped.add(label+"：已在列表");else if(cur.size()>=max)skipped.add(label+"（已满 "+max+" 个）");else{cur.add(label);WatchController.names(cur);added.add(label);}
     next[0].run();});};
   next[0].run();});
  // 超过一屏的显示方式(r10):滚轮(默认,一帧全部行)/ 轮播(网关每 N 秒换页);改了下一次开始生效
  LinearLayout page=EditorialUI.card(a,b);page.addView(EditorialUI.text(a,"超过一屏时",15,EditorialUI.INK));
  page.addView(EditorialUI.text(a,"默认自动轮播：网关按秒换页，首行末尾显示页码。眼镜底部对齐，一页放不下时被挤掉的是第一行，所以每页行数宁少勿多（实测短句 7 行、行情行 6 行）。关掉轮播 = 一帧发全部行、用眼镜滚轮翻看，但固件每次更新都会跳回最后一页（2026-10-20 实测只能停 3 秒），只适合盯最后 7 个。改动在下一次开始生效。",14,EditorialUI.MUTED));
  final TextView[] secs=new TextView[1];
  EditorialUI.toggle(a,page,"自动轮播（网关按秒换页，首行末尾显示页码）",WatchController.rotate(),(v,on)->WatchController.rotate(on));
  final TextView[] psz=new TextView[1];
  psz[0]=EditorialUI.button(a,page,"每页 "+WatchController.pageSize()+" 行（点按切换 5 / 6 / 7，首行被挤掉就调小）",false,()->{int cur=WatchController.pageSize();int next=cur<6?6:cur<7?7:5;WatchController.pageSize(next);psz[0].setText("每页 "+next+" 行（点按切换 5 / 6 / 7，首行被挤掉就调小）");});
  secs[0]=EditorialUI.button(a,page,"轮播每页停留 "+WatchController.pageSeconds()+" 秒（点按切换 3 / 5 / 8 / 10）",false,()->{int cur=WatchController.pageSeconds();int next=cur<5?5:cur<8?8:cur<10?10:3;WatchController.pageSeconds(next);secs[0].setText("轮播每页停留 "+next+" 秒（点按切换 3 / 5 / 8 / 10）");});
  Handler h=new Handler();Runnable tick=new Runnable(){public void run(){if(!s.dialog.isShowing())return;state.setText(NavGlasses.connection()+"\n"+WatchController.status()+(WatchController.lastText().isEmpty()?"":"\n— 最近一帧 —\n"+WatchController.lastText()));h.postDelayed(this,500);}};h.post(tick);
  s.dialog.setOnDismissListener(d->h.removeCallbacks(tick));}
 private static void run(Activity a,String failure){if(failure!=null)EditorialUI.notice(a,failure);}
 /** 列表行上的小动作按钮(↑ ↓ 移除);disabled 时变灰且不响应。 */
 private static TextView act(Activity a,String label,boolean enabled,android.view.View.OnClickListener l){TextView t=EditorialUI.text(a,label,16,enabled?EditorialUI.LIME:EditorialUI.MUTED);t.setPadding(EditorialUI.dp(a,12),EditorialUI.dp(a,8),EditorialUI.dp(a,8),EditorialUI.dp(a,8));if(enabled)t.setOnClickListener(l);return t;}
}
