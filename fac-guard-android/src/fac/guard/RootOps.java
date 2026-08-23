package fac.guard;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class RootOps {
    public static final String TARGET_PACKAGE="com.cocfz.com.freescript";
    private static final String KEEPALIVE="/data/local/tmp/fac_guard_v15_keepalive.sh";
    private static final String KEEPALIVE_PID="/data/local/tmp/fac_guard_v15_keepalive.pid";
    private static final String INSTALL_TEMP="/data/local/tmp/fac_runtime_v15.apk";
    private static final long STAGE_TIMEOUT_MS=20000L;
    private static final long INSTALL_TIMEOUT_MS=30000L;
    private static volatile String lastInstallError="";
    private RootOps() {}

    public interface InstallProgress { void onStage(String text); }

    private static final class CommandResult {
        final int rc; final String out; final boolean timedOut;
        CommandResult(int r,String o,boolean t){rc=r;out=o==null?"":o;timedOut=t;}
    }

    public static boolean hasRoot(){
        try{return run("id").contains("uid=0");}catch(Exception e){return false;}
    }

    public static boolean forceStopTarget(){
        try{run("am force-stop "+TARGET_PACKAGE);return true;}catch(Exception e){return false;}
    }

    public static boolean isTargetRunning(){
        try{return run("pidof "+TARGET_PACKAGE).trim().length()>0;}catch(Exception e){return false;}
    }

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

    public static boolean installPackage(File privateApk){return installPackage(privateApk,null);}

    /**
     * V15.2.1 separates staging and Package Manager installation. The 20 second
     * staging timeout includes the complete 42 MB stdin transfer, so a blocked
     * MuMu root pipe can no longer hang forever before the old install timeout
     * even begins. Package Manager then receives its own 30 second timeout and
     * one independent cmd-package fallback.
     */
    public static boolean installPackage(File privateApk,InstallProgress progress){
        lastInstallError="";
        if(privateApk==null||!privateApk.isFile()){
            lastInstallError="Runtime payload file is missing.";return false;
        }
        try{
            stage(progress,"Staging original runtime...");
            if(!stagePayload(privateApk))return false;

            stage(progress,"Installing original runtime...");
            CommandResult primary=runCommandTimed("pm install -r "+q(INSTALL_TEMP),INSTALL_TIMEOUT_MS);
            if(primary.rc==0&&!primary.timedOut)return true;

            stage(progress,"Retrying Android Package Manager...");
            CommandResult fallback=runCommandTimed("cmd package install -r --user 0 "+q(INSTALL_TEMP),INSTALL_TIMEOUT_MS);
            if(fallback.rc==0&&!fallback.timedOut)return true;

            String first=clean(primary.out),second=clean(fallback.out);
            if(primary.timedOut&&fallback.timedOut){
                lastInstallError="Runtime installation timed out in both Package Manager paths.";
            }else if(fallback.timedOut){
                lastInstallError="Fallback Package Manager timed out after 30 seconds"+(first.length()>0?". First result: "+first:"");
            }else{
                String detail=second.length()>0?second:first;
                lastInstallError="Package Manager denied installation"+(detail.length()>0?": "+detail:"");
            }
            return false;
        }catch(Exception e){
            lastInstallError=e.getMessage()==null?"Root runtime installation failed.":e.getMessage();
            return false;
        }finally{
            removeInstallTemp();
        }
    }

    private static boolean stagePayload(final File privateApk){
        java.lang.Process p=null;
        Thread writer=null,drainer=null;
        final AtomicReference<String> writerError=new AtomicReference<String>();
        final ByteArrayOutputStream captured=new ByteArrayOutputStream();
        try{
            String cmd="rm -f "+q(INSTALL_TEMP)+"; cat > "+q(INSTALL_TEMP)+
                "; RC=$?; if [ $RC -eq 0 ]; then chmod 0644 "+q(INSTALL_TEMP)+
                "; (restorecon "+q(INSTALL_TEMP)+" >/dev/null 2>&1 || true); fi; exit $RC";
            ProcessBuilder pb=new ProcessBuilder("su","-c",cmd);pb.redirectErrorStream(true);p=pb.start();
            final java.lang.Process proc=p;

            drainer=new Thread(new Runnable(){@Override public void run(){
                try{drain(proc.getInputStream(),captured,16384);}catch(Exception ignored){}
            }},"FAC-Stage-Output");
            drainer.start();

            writer=new Thread(new Runnable(){@Override public void run(){
                try(FileInputStream in=new FileInputStream(privateApk);OutputStream out=proc.getOutputStream()){
                    byte[] buf=new byte[128*1024];int n;
                    while((n=in.read(buf))>0)out.write(buf,0,n);
                    out.flush();
                }catch(Exception e){writerError.set(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
            }},"FAC-Stage-Writer");
            writer.start();

            long deadline=SystemClock.elapsedRealtime()+STAGE_TIMEOUT_MS;
            int rc;
            while(true){
                try{rc=p.exitValue();break;}
                catch(IllegalThreadStateException running){
                    if(SystemClock.elapsedRealtime()>=deadline){
                        lastInstallError="Runtime staging timed out after 20 seconds.";
                        try{p.getOutputStream().close();}catch(Exception ignored){}
                        try{p.destroy();}catch(Exception ignored){}
                        if(writer!=null)writer.interrupt();
                        return false;
                    }
                    SystemClock.sleep(50L);
                }
            }
            if(writer!=null)try{writer.join(1200L);}catch(InterruptedException ignored){}
            if(drainer!=null)try{drainer.join(600L);}catch(InterruptedException ignored){}
            String out=clean(new String(captured.toByteArray(),StandardCharsets.UTF_8));
            String we=writerError.get();
            if(rc!=0||we!=null){
                lastInstallError="Runtime staging failed"+(we!=null?": "+we:(out.length()>0?": "+out:""));
                return false;
            }
            return true;
        }catch(Exception e){
            lastInstallError="Runtime staging failed: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
            try{if(p!=null)p.destroy();}catch(Exception ignored){}
            return false;
        }
    }

    private static CommandResult runCommandTimed(String command,long timeoutMs){
        java.lang.Process p=null;Thread drainer=null;final ByteArrayOutputStream captured=new ByteArrayOutputStream();
        try{
            ProcessBuilder pb=new ProcessBuilder("su","-c",command);pb.redirectErrorStream(true);p=pb.start();
            final java.lang.Process proc=p;
            drainer=new Thread(new Runnable(){@Override public void run(){
                try{drain(proc.getInputStream(),captured,32768);}catch(Exception ignored){}
            }},"FAC-PM-Output");
            drainer.start();
            long deadline=SystemClock.elapsedRealtime()+timeoutMs;
            int rc;
            while(true){
                try{rc=p.exitValue();break;}
                catch(IllegalThreadStateException running){
                    if(SystemClock.elapsedRealtime()>=deadline){
                        try{p.destroy();}catch(Exception ignored){}
                        if(drainer!=null)try{drainer.join(500L);}catch(InterruptedException ignored){}
                        return new CommandResult(-1,new String(captured.toByteArray(),StandardCharsets.UTF_8),true);
                    }
                    SystemClock.sleep(75L);
                }
            }
            if(drainer!=null)try{drainer.join(700L);}catch(InterruptedException ignored){}
            return new CommandResult(rc,new String(captured.toByteArray(),StandardCharsets.UTF_8),false);
        }catch(Exception e){
            try{if(p!=null)p.destroy();}catch(Exception ignored){}
            return new CommandResult(-1,e.getMessage(),false);
        }
    }

    private static void stage(InstallProgress p,String text){try{if(p!=null)p.onStage(text);}catch(Exception ignored){}}
    private static String clean(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();}

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
            run(cmd);return true;
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
        try{run("if [ -f "+KEEPALIVE_PID+" ]; then P=$(cat "+KEEPALIVE_PID+" 2>/dev/null); [ -n \"$P\" ] && kill $P >/dev/null 2>&1 || true; fi; rm -f "+KEEPALIVE_PID);}catch(Exception ignored){}
    }

    private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}

    private static void drain(InputStream in,ByteArrayOutputStream capture,int limit)throws IOException{
        byte[] b=new byte[1024];int n,total=0;
        while((n=in.read(b))>0){
            if(total<limit){int take=Math.min(n,limit-total);capture.write(b,0,take);total+=take;}
        }
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
