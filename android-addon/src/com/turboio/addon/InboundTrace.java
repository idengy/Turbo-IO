package com.turboio.addon;

import java.text.SimpleDateFormat;
import java.util.*;

/** bill 分支 r19/r20 诊断(2026-10-21;r20 解业务 15 的 cmd):盯盘/背单词会话期间(NavGlasses 非 idle)记下宿主送来的每个事件——任何 kind、任何业务,
 *  查眼镜长按 / 短按 / 滚轮到底有没有回报手机。只留元数据与前 16 字节(业务 19 解出类型与 json 前 80 字;r20 起业务 15 解出 type、cmd 与 JSON 前 200 字),不记音频体、不记 deviceId;
 *  连续相同的事件合并计数。只读,不改任何宿主行为。 */
final class InboundTrace {
 private static final int MAX=100;
 private static final ArrayDeque<String[]> LOG=new ArrayDeque<>();private static final Map<String,Integer> COUNT=new TreeMap<>();
 private static final SimpleDateFormat CLOCK=new SimpleDateFormat("HH:mm:ss.SSS",Locale.US);
 private static int repeat;private static final java.util.regex.Pattern CMD_KEY=java.util.regex.Pattern.compile(" cmd=(\\S+)");

 static void event(String kind,Map<?,?> data){if(!NavGlasses.active())return;try{record(describe(kind,data));}catch(RuntimeException ignored){}}
 /** 手机侧动作(开始 / 停止)插一行分隔,方便对照按键时间。 */
 static void mark(String what){record("—— "+what);}
 static synchronized void clear(){LOG.clear();COUNT.clear();repeat=0;}

 private static synchronized void record(String line){
  java.util.regex.Matcher m=CMD_KEY.matcher(line);
  String key=line.startsWith("—— ")?"手机动作":line.replaceAll(" .*","")+(m.find()?":"+m.group(1):"");COUNT.merge(key,1,Integer::sum);
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
   if(biz==19){NavCore.Envelope e=NavCore.decode(b);if(e==null)return head+" 解码失败 "+hex(b);return head+" t"+e.type+(e.type==4?" (音频体略)":" "+clip(compact(e.json),80));}
   if(biz==15){String l=launcher(b);if(l!=null)return head+" "+l;}
   return head+" "+hex(b);}
  StringBuilder s=new StringBuilder(kind);int n=0;
  for(Map.Entry<?,?> en:data.entrySet()){if(n++>=6){s.append(" …");break;}s.append(' ').append(clip(String.valueOf(en.getKey()),20)).append('=').append(value(en.getValue()));}
  return s.toString();
 }
 /** r20:业务 15 帧 = protobuf{1 版本,2 类型,3 JSON,4 二进制},解出类型、cmd 与压掉空白的 JSON 前 200 字(不依赖 org.json,桌面可测)。 */
 static String launcher(byte[] b){try{
  int[] p={0};long type=-1;String json=null;int bin=0;
  while(p[0]<b.length){long k=var(b,p);int tag=(int)(k>>>3),wire=(int)(k&7);
   if(wire==2){int n=(int)var(b,p);if(n<0||n>b.length-p[0])return null;if(tag==3)json=new String(b,p[0],n,"UTF-8");else if(tag==4)bin=n;p[0]+=n;}
   else if(wire==0){long v=var(b,p);if(tag==2)type=v;}else return null;}
  StringBuilder s=new StringBuilder("type="+type);if(bin>0)s.append(" bin=").append(bin);
  if(json==null)return s.toString();
  java.util.regex.Matcher m=CMD.matcher(json);s.append(" cmd=").append(m.find()?m.group(1):"?");
  return s.append(' ').append(clip(compact(json),200)).toString();
 }catch(Exception e){return null;}}
 private static final java.util.regex.Pattern CMD=java.util.regex.Pattern.compile("\"cmd\"\\s*:\\s*\"([^\"]{0,40})\"");
 static String compact(String json){return json.replace("\\\"","\"").replaceAll("\\s+"," ").replace("\u0000","");}
 private static long var(byte[] b,int[] p){long n=0;for(int i=0;i<10;i++){if(p[0]>=b.length)throw new IllegalArgumentException();int c=b[p[0]++]&255;n|=(long)(c&127)<<(7*i);if((c&128)==0)return n;}throw new IllegalArgumentException();}
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
  if(LOG.isEmpty())return "入站记录(r20):会话期间暂无事件";
  StringBuilder s=new StringBuilder("入站记录(r20,新的在上)· 计数 ").append(COUNT);
  Iterator<String[]> it=LOG.descendingIterator();for(int i=0;i<n&&it.hasNext();i++){String[] r=it.next();s.append('\n').append(r[0]).append(' ').append(r[1]).append(r[2].isEmpty()?"":" "+r[2]);}
  return s.toString();
 }
 static synchronized String dump(){return summary(MAX);}
}
