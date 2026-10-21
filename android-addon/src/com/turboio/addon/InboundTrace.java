package com.turboio.addon;

import java.text.SimpleDateFormat;
import java.util.*;

/** bill 分支 r19 诊断(2026-10-21):盯盘/背单词会话期间(NavGlasses 非 idle)记下宿主送来的每个事件——任何 kind、任何业务,
 *  查眼镜长按 / 短按 / 滚轮到底有没有回报手机。只留元数据与前 16 字节(业务 19 解出类型与 json 前 60 字),不记音频体、不记 deviceId;
 *  连续相同的事件合并计数。只读,不改任何宿主行为。 */
final class InboundTrace {
 private static final int MAX=60;
 private static final ArrayDeque<String[]> LOG=new ArrayDeque<>();private static final Map<String,Integer> COUNT=new TreeMap<>();
 private static final SimpleDateFormat CLOCK=new SimpleDateFormat("HH:mm:ss.SSS",Locale.US);
 private static int repeat;

 static void event(String kind,Map<?,?> data){if(!NavGlasses.active())return;try{record(describe(kind,data));}catch(RuntimeException ignored){}}
 /** 手机侧动作(开始 / 停止)插一行分隔,方便对照按键时间。 */
 static void mark(String what){record("—— "+what);}
 static synchronized void clear(){LOG.clear();COUNT.clear();repeat=0;}

 private static synchronized void record(String line){
  String key=line.startsWith("—— ")?"手机动作":line.replaceAll(" .*","");COUNT.merge(key,1,Integer::sum);
  String[] last=LOG.peekLast();
  if(last!=null&&last[1].equals(line)){repeat++;last[2]="×"+(repeat+1)+" 至 "+now();return;}
  repeat=0;LOG.addLast(new String[]{now(),line,""});while(LOG.size()>MAX)LOG.removeFirst();
 }
 private static String now(){synchronized(CLOCK){return CLOCK.format(new Date());}}

 static String describe(String kind,Map<?,?> data){
  if(data==null)return kind+" (无数据)";
  if("messageReceived".equals(kind)){Object raw=data.get("message");if(!(raw instanceof Map))return kind+" 非 Map";Map<?,?> m=(Map<?,?>)raw;
   int biz=HostBusiness.id(m.get("businessId"));Object p=m.get("payload");byte[] b=p instanceof byte[]?(byte[])p:new byte[0];
   String head="biz"+biz+"("+clip(String.valueOf(m.get("businessId")),20)+") "+b.length+"B";
   if(biz==19){NavCore.Envelope e=NavCore.decode(b);if(e==null)return head+" 解码失败 "+hex(b);return head+" t"+e.type+(e.type==4?" (音频体略)":" "+clip(e.json,60));}
   return head+" "+hex(b);}
  StringBuilder s=new StringBuilder(kind);int n=0;
  for(Map.Entry<?,?> en:data.entrySet()){if(n++>=6){s.append(" …");break;}s.append(' ').append(clip(String.valueOf(en.getKey()),20)).append('=').append(value(en.getValue()));}
  return s.toString();
 }
 private static String value(Object v){
  if(v==null)return "null";if(v instanceof Number||v instanceof Boolean)return v.toString();
  if(v instanceof String)return clip((String)v,24);if(v instanceof byte[])return "bytes"+((byte[])v).length;
  if(v instanceof Map)return "{"+((Map<?,?>)v).size()+"}";if(v instanceof Collection)return "["+((Collection<?>)v).size()+"]";
  return v.getClass().getSimpleName();
 }
 private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(int i=0;i<Math.min(16,b.length);i++)s.append(String.format("%02x",b[i]&255));return b.length>16?s+"…":s.toString();}
 private static String clip(String s,int max){return s==null?"":s.length()>max?s.substring(0,max)+"…":s;}

 /** 页面显示:类型计数 + 最近 n 行(新的在上)。 */
 static synchronized String summary(int n){
  if(LOG.isEmpty())return "入站记录(r19):会话期间暂无事件";
  StringBuilder s=new StringBuilder("入站记录(r19,新的在上)· 计数 ").append(COUNT);
  Iterator<String[]> it=LOG.descendingIterator();for(int i=0;i<n&&it.hasNext();i++){String[] r=it.next();s.append('\n').append(r[0]).append(' ').append(r[1]).append(r[2].isEmpty()?"":" "+r[2]);}
  return s.toString();
 }
 static synchronized String dump(){return summary(MAX);}
}
