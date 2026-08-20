package fac.guard;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class RootOps {
    public static final String TARGET_PACKAGE="com.cocfz.com.freescript";
    private static final String KEEPALIVE="/data/local/tmp/fac_guard_v15_keepalive.sh";
    private static final String KEEPALIVE_PID="/data/local/tmp/fac_guard_v15_keepalive.pid";
    private static final String INSTALL_TEMP="/data/local/tmp/fac_runtime_v15.apk";
    private static volatile String lastInstallError="";
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

    /** Grants only the two UI privileges V15.2 needs on the rooted emulator. */
    public static boolean enableUiPrivileges(Context c){
        if(c==null)return false;
        try{
            String pkg=c.getPackageName();
            run("appops set "+q(pkg)+" SYSTEM_ALERT_WINDOW allow");
            String component=pkg+"/"+TextMaskAccessibilityService.class.getName();
            String enabled=run("settings get secure enabled_accessibility_services").trim();
            if("null".equalsIgnoreCase(enabled))enabled="";
            if(!enabled.contains(component)){
                String next=enabled.length()==0?component:enabled+":"+component;
                run("settings put secure enabled_accessibility_services "+q(next));
            }
            run("settings put secure accessibility_enabled 1");
            return true;
        }catch(Exception e){return false;}
    }

    public static boolean isTextMaskAccessibilityEnabled(Context c){
        if(c==null)return false;
        try{
            String component=c.getPackageName()+"/"+TextMaskAccessibilityService.class.getName();
            return run("settings get secure enabled_accessibility_services").contains(component);
        }catch(Exception e){return false;}
    }

    /**
     * Streams the verified plaintext APK through su stdin. Root never has to
     * traverse /data/user/0/fac.guard, avoiding the MuMu/SELinux "denied" path
     * seen in V15. The whole install is capped at ~40 seconds.
     */
    public static boolean installPackage(File privateApk){
        lastInstallError="";
        if(privateApk==null||!privateApk.isFile()){lastInstallError="Runtime payload file is missing.";return false;}
        java.lang.Process p=null;
        try{
            String tmp=q(INSTALL_TEMP);
            String cmd="rm -f "+tmp+
                "; cat > "+tmp+
                " && chmod 0644 "+tmp+
                " && (restorecon "+tmp+" >/dev/null 2>&1 || true)"+
                " && pm install --user 0 -r "+tmp+
                "; RC=$?; rm -f "+tmp+"; exit $RC";
            ProcessBuilder pb=new ProcessBuilder("su","-c",cmd);pb.redirectErrorStream(true);p=pb.start();

            try(FileInputStream in=new FileInputStream(privateApk);OutputStream rootIn=p.getOutputStream()){
                byte[] buf=new byte[128*1024];int n;
                while((n=in.read(buf))>0)rootIn.write(buf,0,n);
                rootIn.flush();
            }

            long deadline=SystemClock.elapsedRealtime()+40000L;
            while(true){
                try{
                    int rc=p.exitValue();
                    String out=readBounded(p.getInputStream(),16384).trim();
                    if(rc==0&&(out.contains("Success")||out.length()==0))return true;
                    lastInstallError="Package Manager denied installation"+(out.length()>0?": "+out:"");
                    return false;
                }catch(IllegalThreadStateException stillRunning){
                    if(SystemClock.elapsedRealtime()>=deadline){
                        lastInstallError="Runtime installation timed out after 40 seconds.";
                        try{p.destroy();}catch(Exception ignored){}
                        return false;
                    }
                    SystemClock.sleep(100L);
                }
            }
        }catch(Exception e){
            lastInstallError=e.getMessage()==null?"Root runtime installation failed.":e.getMessage();
            try{if(p!=null)p.destroy();}catch(Exception ignored){}
            return false;
        }finally{
            removeInstallTemp();
        }
    }

    public static String lastInstallError(){return lastInstallError==null?"":lastInstallError;}

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
            String cmd="am start -n "+pkg+"/.MainActivityV152 --activity-clear-top --ez intercepted true --es reason "+q(reason==null?"blocked":reason);
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

    private static String readBounded(InputStream in,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n,total=0;
        while((n=in.read(b))>0&&total<limit){int take=Math.min(n,limit-total);out.write(b,0,take);total+=take;}
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }

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
