package com.turboio.addon;

import java.lang.reflect.*;

/** Version-scoped public API adapter. No vendor code copied. */
final class NavReflect {
    // Call sites keep the upstream 1.0.5 logical names. select() maps them to the installed
    // host's obfuscated names (1.0.6 relocation evidence: custom/docs/HOST_1.0.6.md).
    private static String sender="E3.u",message="E3.Q",business="P3.h",priority="E3.b",core="Q3.q",result="P3.A",send="u",sendFile="v";
    private static int selected; // host versionCode once mapped; 0 = not mapped yet
    private static boolean guarded; // assets/turboio/official-ota-guarded.txt present (package-official-ota-preparation.py --guarded)
    static synchronized void select(int versionCode)throws Exception{
        if(versionCode==216){sender="X3.u";message="X3.Q";business="i4.h";priority="X3.b";core="j4.q";result="i4.A";send="t";sendFile="u";}
        else if(versionCode==201){sender="E3.u";message="E3.Q";business="P3.h";priority="E3.b";core="Q3.q";result="P3.A";send="u";sendFile="v";}
        else throw new IllegalArgumentException("Unsupported host versionCode "+versionCode);
        Class.forName(sender);
        selected=versionCode;
    }
    // Smali hooks (event bridge, OTA gates) can fire before MainActivity.onPostResume calls
    // TurboAddon.install; resolve the host lazily so no call ever runs with unmapped names.
    private static synchronized void ensure()throws Exception{
        if(selected!=0)return;
        Object c=Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        if(!(c instanceof android.content.Context))throw new IllegalStateException("Host application unavailable");
        android.content.Context app=((android.content.Context)c).getApplicationContext();
        select(app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionCode);
        try{String[] files=app.getAssets().list("turboio");guarded=files!=null&&java.util.Arrays.asList(files).contains("official-ota-guarded.txt");}catch(Exception e){guarded=false;}
    }
    /** True when this package may drive OTA: upstream 1.0.5 (201) builds always carry the smali queue/file gates; on 1.0.6
     * only the guarded package (route-patched libapp.so + marker asset) does. A plain 1.0.6 package stays disabled (A-segment). */
    static boolean otaGatesInstalled(){try{ensure();}catch(Exception e){return false;}return selected==201||guarded;}
    private static String host(String name)throws Exception{
        ensure();
        if(name.equals("E3.u"))return sender;
        if(name.equals("E3.Q"))return message;
        if(name.equals("P3.h"))return business;
        if(name.equals("E3.b"))return priority;
        if(name.equals("Q3.q"))return core;
        if(name.equals("P3.A"))return result;
        return name;
    }
    static Class<?> type(String name)throws Exception{return Class.forName(host(name));}
    static Object field(Object target,String name)throws Exception{return (target instanceof Class?(Class<?>)target:target.getClass()).getField(name).get(target instanceof Class?null:target);}
    private static Class<?> boxed(Class<?> c){if(c==boolean.class)return Boolean.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==float.class)return Float.class;if(c==double.class)return Double.class;return c;}
    private static boolean fits(Class<?>[] types,Object[] args){if(types.length!=args.length)return false;for(int i=0;i<types.length;i++){if(args[i]==null){if(types[i].isPrimitive())return false;}else if(!boxed(types[i]).isInstance(args[i]))return false;}return true;}
    static Object make(String name,Object...args)throws Exception{for(Constructor<?> c:type(name).getConstructors())if(fits(c.getParameterTypes(),args))return c.newInstance(args);throw new NoSuchMethodException(name);}
    static Object call(Object o,String name,Object...args)throws Exception{
        Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();
        // The sender's instance methods moved one slot in 1.0.6: send u->t, file v->u. Static calls (g) are untouched.
        if(!(o instanceof Class)){ensure();if(c.getName().equals(sender)){if(name.equals("u"))name=send;else if(name.equals("v"))name=sendFile;}}
        for(Method m:c.getMethods())if(m.getName().equals(name)&&fits(m.getParameterTypes(),args))return m.invoke(o instanceof Class?null:o,args);
        throw new NoSuchMethodException(name);
    }
    interface Callback{void accept(String name,Object[] args)throws Exception;}
    static Object proxy(String name,Callback handler)throws Exception{Class<?> t=type(name);return Proxy.newProxyInstance(t.getClassLoader(),new Class[]{t},(o,m,a)->{if(m.getDeclaringClass()==Object.class){if(m.getName().equals("hashCode"))return System.identityHashCode(o);if(m.getName().equals("equals"))return o==a[0];return "TurboIOAdapter";}handler.accept(m.getName(),a==null?new Object[0]:a);return null;});}
}
