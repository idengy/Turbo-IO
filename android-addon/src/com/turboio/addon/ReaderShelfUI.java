package com.turboio.addon;
import android.app.*;import android.os.*;import android.view.View;import android.widget.*;import java.util.*;
/** 书架管理窗口(bill 分支 r17,2026-10-21):独立对话框列出全部书(不按 4 本分页),每本可上移 / 下移 / 置顶 / 删除。
 *  改动即保存到 reader-shelf.json 并重编 token;删除同时删正文文件。眼镜侧书架只在主页面「发送四本书架到眼镜」时刷新。
 *  阅读进行中或正在同步时拒绝改动(与 importText 同一条件)。 */
final class ReaderShelfUI {
 static void show(Activity a,Runnable changed){
  EditorialUI.Screen s=new EditorialUI.Screen(a,"管理书架");LinearLayout b=s.body;
  b.addView(EditorialUI.text(a,"全部书按眼镜书架顺序排列，每 4 本一页。↑↓ 调顺序，「顶」置顶，「删」删除这本书及其正文（本机导入的书删了就没了；微信书架同步来的书下次同步会回来）。阅读进行中不能改。",14,EditorialUI.MUTED));
  TextView state=EditorialUI.text(a,"",14,EditorialUI.LIME);b.addView(state);
  LinearLayout list=EditorialUI.column(a);b.addView(list);
  final Runnable[] render=new Runnable[1];render[0]=()->{list.removeAllViews();List<ReaderCodec.Book> books=ReaderLibrary.books;state.setText("共 "+books.size()+" 本 · "+ReaderLibrary.note);
   if(books.isEmpty())list.addView(EditorialUI.text(a,"书架是空的。回上一页用「新增本机 TXT」导入，或「载入内置测试书」。",14,EditorialUI.MUTED));
   for(int idx=0;idx<books.size();idx++){final int i=idx;final ReaderCodec.Book book=books.get(i);
    LinearLayout row=new LinearLayout(a);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(0,EditorialUI.dp(a,6),0,EditorialUI.dp(a,6));
    TextView t=EditorialUI.text(a,(i+1)+". "+book.title+"\n"+(book.author==null||book.author.isEmpty()?"":book.author)+(i%4==0?"  · 第 "+(i/4+1)+" 页":""),15,EditorialUI.INK);row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
    row.addView(act(a,"↑",i>0,v->apply(a,ReaderLibrary.move(book,-1),render[0])));
    row.addView(act(a,"↓",i<books.size()-1,v->apply(a,ReaderLibrary.move(book,1),render[0])));
    row.addView(act(a,"顶",i>0,v->apply(a,ReaderLibrary.move(book,-i),render[0])));
    row.addView(act(a,"删",true,v->EditorialUI.confirm(a,"删除「"+book.title+"」？","连同已导入的正文一起删除，不可恢复。",()->apply(a,ReaderLibrary.remove(book),render[0]))));
    list.addView(row);}};
  render[0].run();
  EditorialUI.button(a,b,"完成",true,()->s.dialog.dismiss());
  s.dialog.setOnDismissListener(d->{if(changed!=null)changed.run();});}
 private static void apply(Activity a,String failure,Runnable render){if(failure!=null)EditorialUI.notice(a,failure);render.run();}
 private static TextView act(Activity a,String label,boolean enabled,View.OnClickListener l){TextView t=EditorialUI.text(a,label,16,enabled?EditorialUI.LIME:EditorialUI.MUTED);t.setPadding(EditorialUI.dp(a,10),EditorialUI.dp(a,8),EditorialUI.dp(a,6),EditorialUI.dp(a,8));if(enabled)t.setOnClickListener(l);return t;}
}
