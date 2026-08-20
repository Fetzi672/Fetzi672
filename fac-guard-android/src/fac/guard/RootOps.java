package fac.guard;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class RootOps {
    public static final String TARGET_PACKAGE="com.cocfz.com.freescript";
    private static final String KEEPALIVE="/data/local/tmp/fac_guard_v15_keepalive.sh";
    private static final String KEEPALIVE_PID="/data/local/tmp/fac_guard_v15_keepalive.pid";
    private static final String INSTALL_TEMP="/data/local/tmp/fac_runtime_v15.apk";
    private RootOps() {}

    public static boolean hasRoot(){
        try{return run("id").contains("uid=0");}catch(Exception e){return false;}
    }

    public static boolean forceStopTarget(){
        try{run("am force-stop "+TARGET_PACKAGE);return true;}catch(Exception e){return false;}
    }

    public static boolean isTargetRunning(){
        try{return run("pidof "+TARGET_PACKAGE).trim().length()>0;}catch(Exception e){return false;}
    }

    public static boolean installPackage(File privateApk){
        if(privateApk==null||!privateApk.isFile())return false;
        try{
            String src=q(privateApk.getAbsolutePath());
            String tmp=q(INSTALL_TEMP);
            String cmd="rm -f "+tmp+"; cp "+src+" "+tmp+"; chmod 0644 "+tmp+
                "; pm install -r "+tmp+"; RC=$?; rm -f "+tmp+"; exit $RC";
            String result=run(cmd);
            return result.contains("Success")||result.trim().length()==0;
        }catch(Exception e){return false;}
    }

    public static void removeInstallTemp(){
        try{run("rm -f "+q(INSTALL_TEMP));}catch(Exception ignored){}
    }

    public static java.lang.Process startTargetMonitor()throws Exception{
        String cmd="while true; do if pidof "+TARGET_PACKAGE+" >/dev/null 2>&1; then echo RUN; else echo STOP; fi; sleep 0.5; done";
        return Runtime.getRuntime().exec(new String[]{"su","-c",cmd});
    }

    public static boolean openGuardUi(Context c,String reason){
        try{
            String pkg=c.getPackageName();
            String cmd="am start -n "+pkg+"/.MainActivity --activity-clear-top --ez intercepted true --es reason "+q(reason==null?"blocked":reason);
            run(cmd);
            return true;
        }catch(Exception e){return false;}
    }

    public static boolean installKeepalive(Context c){
        File local=new File(c.getFilesDir(),"fac_guard_v15_keepalive.sh");
        try{
            String pkg=c.getPackageName();
            String script="#!/system/bin/sh\n"+
                "echo $$ > "+KEEPALIVE_PID+"\n"+
                "while true; do\n"+
                "  if ! pidof "+pkg+" >/dev/null 2>&1; then\n"+
                "    if pidof "+TARGET_PACKAGE+" >/dev/null 2>&1; then am force-stop "+TARGET_PACKAGE+" >/dev/null 2>&1; fi\n"+
                "    am start-foreground-service -n "+pkg+"/.GuardService >/dev/null 2>&1 || am startservice -n "+pkg+"/.GuardService >/dev/null 2>&1\n"+
                "  fi\n"+
                "  sleep 3\n"+
                "done\n";
            FileOutputStream out=new FileOutputStream(local,false);
            out.write(script.getBytes(StandardCharsets.UTF_8));out.flush();out.getFD().sync();out.close();
            String stop="if [ -f "+KEEPALIVE_PID+" ]; then P=$(cat "+KEEPALIVE_PID+" 2>/dev/null); [ -n \"$P\" ] && kill $P >/dev/null 2>&1 || true; fi; rm -f "+KEEPALIVE_PID;
            run(stop+"; cp "+q(local.getAbsolutePath())+" "+KEEPALIVE+"; chmod 700 "+KEEPALIVE+"; nohup /system/bin/sh "+KEEPALIVE+" >/dev/null 2>&1 </dev/null &");
            return true;
        }catch(Exception e){return false;}
    }

    public static void stopKeepalive(){
        try{
            run("if [ -f "+KEEPALIVE_PID+" ]; then P=$(cat "+KEEPALIVE_PID+" 2>/dev/null); [ -n \"$P\" ] && kill $P >/dev/null 2>&1 || true; fi; rm -f "+KEEPALIVE_PID);
        }catch(Exception ignored){}
    }

    private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}

    private static String run(String command)throws Exception{
        java.lang.Process p=Runtime.getRuntime().exec(new String[]{"su","-c",command});
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        InputStream in=p.getInputStream();byte[] b=new byte[1024];int n,total=0;
        while((n=in.read(b))>0&&total<8192){int take=Math.min(n,8192-total);out.write(b,0,take);total+=take;}
        in.close();
        InputStream err=p.getErrorStream();
        while((n=err.read(b))>0&&total<8192){int take=Math.min(n,8192-total);out.write(b,0,take);total+=take;}
        err.close();
        int rc=p.waitFor();
        String s=new String(out.toByteArray(),StandardCharsets.UTF_8);
        if(rc!=0)throw new IllegalStateException("su command failed: "+rc+" "+s);
        return s;
    }
}
