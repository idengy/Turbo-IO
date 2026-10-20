package com.turboio.addon;

import android.content.Context;
import android.os.*;
import java.util.*;

/** 期货盯盘(bill 分支):把行情文字推到眼镜的业务 19 显示面板(经 NavGlasses)。
 *  P0 = 实机探针(合成行情 / 宽度 / 替换-追加),用来测固件在 1.0.6 宿主 + TMIG1 上的面板行为;
 *  完整模式(网关 SSE 订阅、55 分钟续期、后台租约、语音启停)在 P0 结论之后加。全部在主线程调用。 */
public final class WatchController {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final String[] NAMES={"螺纹","铁矿","焦煤","豆粕","沪铜"};
    private static final double[] BASE={3215,781.5,1120,2890,78540},STEP={1,0.5,2,1,10};
    private static Context app;
    private static long epoch;private static String mode="",note="未开始";private static int offers,seconds;
    private static final double[] price=new double[5];private static final Random random=new Random();

    static void init(Context c){app=c.getApplicationContext();}
    static String status(){return note+(mode.isEmpty()?"":" · "+mode+" · 已推 "+offers+" 帧 · "+seconds+" s");}
    static boolean running(){return !mode.isEmpty();}

    /** 合成 5 行随机游走行情,1 s 一帧,跑 180 s 后请求关闭。返回 null = 已开始;否则是不能开始的原因。 */
    static String startTest(){return begin("合成行情",frame());}
    /** "width":5 行递增长度的文字,看每行在哪里截断;"append":A 帧 4 s 后 B 帧,看面板是替换还是叠加。 */
    static String startProbe(String kind){
        if(kind.equals("width"))return begin("宽度探针",widthFrame());
        if(kind.equals("append"))return begin("替换探针","A 第一帧 请记住这行\n(4 秒后换 B 帧)");
        return "未知探针";
    }
    private static String begin(String m,String first){
        if(running())return "探针进行中，请先停止";
        if(NavGlasses.active())return "上一显示会话未结束（"+NavGlasses.phase()+"），请先确认眼镜已回首页";
        for(int i=0;i<5;i++)price[i]=BASE[i];
        if(!NavGlasses.startCaption(first))return "无法开启眼镜显示："+NavGlasses.status();
        epoch++;mode=m;offers=1;seconds=0;note="已开始，看眼镜";
        final long e=epoch;MAIN.postDelayed(new Runnable(){public void run(){if(e!=epoch)return;seconds++;step();if(running())MAIN.postDelayed(this,1000);}},1000);
        return null;
    }
    private static void step(){
        if(mode.equals("合成行情")){
            if(seconds>=180){stop("3 分钟测试结束，已请求关闭显示");return;}
            NavGlasses.offer(frame());offers++;
        }else if(mode.equals("替换探针")){
            if(seconds==4){NavGlasses.offer("B 第二帧 若仍能看到 A 行\n说明面板是追加");offers++;}
            else if(seconds>=60)stop("替换探针结束，已请求关闭显示");
        }else if(seconds>=120)stop("宽度探针结束，已请求关闭显示");
        if(NavGlasses.phase().equals("uncertain")||NavGlasses.phase().equals("idle")){String p=NavGlasses.phase();stop("眼镜会话已结束（"+p+"），探针停止");}
    }
    static void stop(String reason){epoch++;mode="";note=reason;if(NavGlasses.active())NavGlasses.stop();}

    private static String frame(){
        StringBuilder b=new StringBuilder();
        for(int i=0;i<5;i++){
            price[i]+=STEP[i]*(random.nextInt(5)-2);
            if(i>0)b.append('\n');
            b.append(String.format(Locale.ROOT,"%s %s %+.2f%%",NAMES[i],priceText(price[i]),(price[i]/BASE[i]-1)*100));
        }
        return b.toString();
    }
    static String priceText(double v){if(v>=100&&v==Math.rint(v))return String.valueOf((long)v);if(v>=100)return String.format(Locale.ROOT,"%.1f",v);return String.format(Locale.ROOT,"%.2f",v);}
    private static String widthFrame(){
        String cjk="一二三四五六七八九十甲乙丙丁戊己庚辛壬癸";
        StringBuilder b=new StringBuilder();int[] lens={10,12,14,16,18};
        for(int i=0;i<5;i++){if(i>0)b.append('\n');b.append(i+1).append(cjk,0,lens[i]);}
        return b.toString();
    }
}
