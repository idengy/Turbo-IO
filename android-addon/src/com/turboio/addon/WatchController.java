package com.turboio.addon;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** 期货盯盘(bill 分支):订阅 Mac 网关的 SSE 行情文字流,每帧 `NavGlasses.offer(text)` 推到眼镜业务 19 文字面板。
 *  排版在网关(≤4 品种首行时钟、每行「短名 价格 涨跌」),这里只做:品种选择(≤5)、SSE 订阅与退避重连、
 *  55 分钟续期(WatchSession 状态机 + NavGlasses.renew())、BackgroundWork 租约、语音启停。全部在主线程调用。
 *  流地址 = 模型端点(…/v1/chat/completions)的 origin + /v1/watch/stream,Key 复用 SecretStore 的 model_key。
 *  另保留 P0 探针(合成行情 / 宽度 / 替换-追加),用于固件或宿主升级后复测面板行为。 */
public final class WatchController {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    static final int MAX_NAMES=5;
    private static Context app;private static SharedPreferences prefs;
    // 完整模式
    private static long epoch;private static boolean running,renewing;private static long sessionStart,closeAt,lastData;private static int attempt;
    private static String note="未开始",lastText="",streamUrl="";private static volatile HttpURLConnection connection;
    // P0 探针
    private static final String[] NAMES={"螺纹","铁矿","焦煤","豆粕","沪铜"};
    private static final double[] BASE={3215,781.5,1120,2890,78540},STEP={1,0.5,2,1,10};
    private static String mode="";private static int offers,seconds;
    private static final double[] price=new double[5];private static final Random random=new Random();

    static void init(Context c){app=c.getApplicationContext();prefs=app.getSharedPreferences("turboio_watch",0);BackgroundWork.register("盯盘",()->stop("从后台通知停止"));}

    // ---- 品种 ----
    static List<String> names(){List<String> out=new ArrayList<>();for(String n:prefs.getString("names","").split(","))if(!n.trim().isEmpty())out.add(n.trim());return out;}
    static void names(List<String> n){StringBuilder b=new StringBuilder();for(String x:n.subList(0,Math.min(n.size(),MAX_NAMES))){if(b.length()>0)b.append(',');b.append(x);}prefs.edit().putString("names",b.toString()).apply();}
    /** 缓存的品种表:每项 {name, short};空 = 还没拉过。 */
    static List<String[]> catalog(){List<String[]> out=new ArrayList<>();for(String e:prefs.getString("catalog","").split(",")){String[] p=e.split("\\|");if(p.length==2&&!p[0].isEmpty())out.add(p);}return out;}
    interface Done{void run(String failure);}
    /** 后台 GET /v1/watch/names 刷新品种表;回调在主线程,failure 为 null 表示成功。 */
    static void refreshCatalog(Done done){
        final String url,key;try{url=origin()+"/v1/watch/names";key=SecretStore.get(app);}catch(Exception e){done.run("请先在「模型与对话」配置自有模型地址与 Key");return;}
        if(key.isEmpty()){done.run("模型 Key 为空，请先在「模型与对话」配置");return;}
        Thread t=new Thread(()->{String failure=null;HttpURLConnection http=null;
            try{http=(HttpURLConnection)new URL(url).openConnection();http.setConnectTimeout(15000);http.setReadTimeout(20000);http.setInstanceFollowRedirects(false);http.setRequestProperty("Authorization","Bearer "+key);
                if(http.getResponseCode()!=200)throw new IOException("HTTP "+http.getResponseCode());
                String body=read(http.getInputStream(),256*1024);JSONArray arr=new JSONObject(body).getJSONArray("names");StringBuilder b=new StringBuilder();
                for(int i=0;i<arr.length();i++){JSONObject o=arr.getJSONObject(i);String n=o.getString("name").replace(",","").replace("|",""),sh=o.optString("short",n).replace(",","").replace("|","");if(b.length()>0)b.append(',');b.append(n).append('|').append(sh);}
                prefs.edit().putString("catalog",b.toString()).apply();
            }catch(Exception e){failure="品种表获取失败："+e.getClass().getSimpleName();}
            finally{if(http!=null)http.disconnect();}
            final String f=failure;MAIN.post(()->done.run(f));},"TurboIO-watch-names");t.setDaemon(true);t.start();
    }
    private static String read(InputStream in,int cap)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0){out.write(buf,0,n);if(out.size()>cap)throw new IOException("too large");}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
    /** 模型端点的 origin(https://host[:port]);端点不合法时抛异常。 */
    static String origin()throws Exception{String endpoint=app.getSharedPreferences("turboio_settings",0).getString("endpoint","");if(!ChatPolicy.endpoint(endpoint))throw new IllegalStateException("endpoint");URL u=new URL(endpoint);return u.getProtocol()+"://"+u.getHost()+(u.getPort()>0?":"+u.getPort():"");}

    // ---- 状态 ----
    static boolean running(){return running;}
    static String status(){
        StringBuilder b=new StringBuilder(note);
        if(running){long age=lastData>0?(SystemClock.elapsedRealtime()-lastData)/1000:-1;b.append(" · 品种 ").append(names()).append(age<0?" · 等待首帧":" · 最近数据 "+age+" s 前").append(WatchSession.stale(SystemClock.elapsedRealtime(),lastData)?" · 可能停滞":"").append(renewing?" · 续期中":"");
            b.append(" · 会话 ").append((SystemClock.elapsedRealtime()-sessionStart)/60000).append(" 分钟");}
        if(!mode.isEmpty())b.append(" · ").append(mode).append(" · 已推 ").append(offers).append(" 帧 · ").append(seconds).append(" s");
        return b.toString();
    }
    static String lastText(){return lastText;}

    // ---- 完整模式 ----
    /** 能否开始;null = 可以,否则是给用户看的原因。 */
    static String precheck(){
        if(running)return "盯盘已在进行";
        if(!mode.isEmpty())return "探针进行中，请先停止";
        if(names().isEmpty())return "请先在手机勾选品种（最多 5 个）";
        if(NavGlasses.active())return "上一显示会话未结束（"+NavGlasses.phase()+"），请先确认眼镜已回首页";
        try{origin();}catch(Exception e){return "请先在「模型与对话」配置自有模型地址（https，以 /chat/completions 结尾）";}
        try{if(SecretStore.get(app).isEmpty())return "模型 Key 为空，请先在「模型与对话」配置";}catch(Exception e){return "读取模型 Key 失败";}
        return null;
    }
    /** 开始盯盘;返回 null = 已开始,否则原因。 */
    static String start(){
        String why=precheck();if(why!=null)return why;
        final String key;try{StringBuilder q=new StringBuilder();for(String n:names()){if(q.length()>0)q.append(',');q.append(n);}streamUrl=origin()+"/v1/watch/stream?names="+URLEncoder.encode(q.toString(),"UTF-8");key=SecretStore.get(app);}catch(Exception e){return "拼接流地址失败";}
        if(!NavGlasses.startCaption("盯盘连接中…"))return "无法开启眼镜显示："+NavGlasses.status();
        BackgroundWork.acquire("盯盘",16);
        epoch++;running=true;renewing=false;attempt=0;lastData=0;lastText="";sessionStart=SystemClock.elapsedRealtime();note="已开始，等待行情流";
        final long e=epoch;spawn(e,key);MAIN.removeCallbacks(tick);MAIN.postDelayed(tick,1000);
        return null;
    }
    static void stop(String reason){
        boolean was=running;running=false;renewing=false;epoch++;MAIN.removeCallbacks(tick);
        final HttpURLConnection c=connection;connection=null;if(c!=null){Thread t=new Thread(c::disconnect,"TurboIO-watch-close");t.setDaemon(true);t.start();}
        if(!mode.isEmpty())mode="";
        if(NavGlasses.active())NavGlasses.stop();
        if(was)BackgroundWork.release("盯盘");
        note=reason;
    }
    /** 语音「开始盯盘」:先校验,再延迟 1.5 s 真正开始(让官方回答区先关闭);返回给眼镜说的话。 */
    static String voiceStart(){
        String why=precheck();if(why!=null)return why;
        MAIN.postDelayed(()->{String r=start();if(r!=null)note="语音开始失败："+r;},1500);
        return "盯盘已开始，手机可切换品种";
    }
    static String voiceStop(){if(!running)return "当前没有在盯盘";MAIN.postDelayed(()->stop("语音停止"),300);return "盯盘已停止";}

    private static void spawn(final long e,final String key){
        Thread t=new Thread(()->{HttpURLConnection http=null;String why="流已结束";
            try{http=(HttpURLConnection)new URL(streamUrl).openConnection();connection=http;
                http.setConnectTimeout(15000);http.setReadTimeout(30000);http.setInstanceFollowRedirects(false);
                http.setRequestProperty("Authorization","Bearer "+key);http.setRequestProperty("Accept","text/event-stream");
                int code=http.getResponseCode();if(code!=200)throw new IOException("HTTP "+code);
                WatchStream parser=new WatchStream();long bytes=0;
                try(BufferedReader r=new BufferedReader(new InputStreamReader(http.getInputStream(),StandardCharsets.UTF_8))){
                    String line;while((line=r.readLine())!=null){if(e!=epoch)return;bytes+=line.length()+1;if(bytes>(1<<20))throw new IOException("stream too large");
                        String ev=parser.accept(line);if(ev==null)continue;final String text=WatchStream.text(ev);if(text==null)continue;
                        MAIN.post(()->onText(e,text));}
                }
            }catch(Exception ex){why=ex instanceof IllegalStateException?ex.getMessage():ex.getClass().getSimpleName();}
            finally{if(http!=null)try{http.disconnect();}catch(Exception ignored){}}
            final String w=why;MAIN.post(()->onDrop(e,w,key));},"TurboIO-watch");
        t.setDaemon(true);t.start();
    }
    private static void onText(long e,String text){if(e!=epoch||!running)return;lastText=text;lastData=SystemClock.elapsedRealtime();attempt=0;note="行情更新中";String p=NavGlasses.phase();if(p.equals("ready")||p.equals("starting"))NavGlasses.offer(text);}
    private static void onDrop(long e,String why,String key){if(e!=epoch||!running)return;attempt++;long d=WatchSession.backoff(attempt);note="行情流断开（"+why+"），"+d/1000+" 秒后重连";MAIN.postDelayed(()->{if(e==epoch&&running)spawn(e,key);},d);}

    private static final Runnable tick=new Runnable(){public void run(){
        if(!running)return;long now=SystemClock.elapsedRealtime();String phase=NavGlasses.phase();
        WatchSession.Act act=WatchSession.onTick(now,sessionStart,phase,renewing,closeAt);
        if(act==WatchSession.Act.RENEW){renewing=true;closeAt=now;note="会话满 55 分钟，续期中";NavGlasses.renew();}
        else if(act==WatchSession.Act.REOPEN){renewing=false;if(!NavGlasses.startCaption(lastText.isEmpty()?"盯盘连接中…":lastText)){stop("续期后无法重新打开显示："+NavGlasses.status());return;}sessionStart=now;note="已续期";}
        else if(act==WatchSession.Act.STOP){stop(WatchSession.stopReason(phase,renewing));return;}
        MAIN.postDelayed(this,1000);
    }};

    // ---- P0 探针(保留) ----
    static String startTest(){return begin("合成行情",frame());}
    static String startProbe(String kind){
        if(kind.equals("width"))return begin("宽度探针",widthFrame());
        if(kind.equals("append"))return begin("替换探针","A 第一帧 请记住这行\n(4 秒后换 B 帧)");
        return "未知探针";
    }
    private static String begin(String m,String first){
        if(running)return "盯盘进行中，请先停止";
        if(!mode.isEmpty())return "探针进行中，请先停止";
        if(NavGlasses.active())return "上一显示会话未结束（"+NavGlasses.phase()+"），请先确认眼镜已回首页";
        for(int i=0;i<5;i++)price[i]=BASE[i];
        if(!NavGlasses.startCaption(first))return "无法开启眼镜显示："+NavGlasses.status();
        epoch++;mode=m;offers=1;seconds=0;note="已开始，看眼镜";
        final long e=epoch;MAIN.postDelayed(new Runnable(){public void run(){if(e!=epoch)return;seconds++;step();if(!mode.isEmpty())MAIN.postDelayed(this,1000);}},1000);
        return null;
    }
    private static void step(){
        if(mode.equals("合成行情")){if(seconds>=180){stop("3 分钟测试结束，已请求关闭显示");return;}NavGlasses.offer(frame());offers++;}
        else if(mode.equals("替换探针")){if(seconds==4){NavGlasses.offer("B 第二帧 若仍能看到 A 行\n说明面板是追加");offers++;}else if(seconds>=60){stop("替换探针结束，已请求关闭显示");return;}}
        else if(seconds>=120){stop("宽度探针结束，已请求关闭显示");return;}
        String p=NavGlasses.phase();if(p.equals("uncertain")||p.equals("idle"))stop("眼镜会话已结束（"+p+"），探针停止");
    }
    private static String frame(){StringBuilder b=new StringBuilder();for(int i=0;i<5;i++){price[i]+=STEP[i]*(random.nextInt(5)-2);if(i>0)b.append('\n');b.append(String.format(Locale.ROOT,"%s %s %+.2f%%",NAMES[i],priceText(price[i]),(price[i]/BASE[i]-1)*100));}return b.toString();}
    static String priceText(double v){if(v>=100&&v==Math.rint(v))return String.valueOf((long)v);if(v>=100)return String.format(Locale.ROOT,"%.1f",v);return String.format(Locale.ROOT,"%.2f",v);}
    private static String widthFrame(){String cjk="一二三四五六七八九十甲乙丙丁戊己庚辛壬癸";StringBuilder b=new StringBuilder();int[] lens={10,12,14,16,18};for(int i=0;i<5;i++){if(i>0)b.append('\n');b.append(i+1).append(cjk,0,lens[i]);}return b.toString();}
}
