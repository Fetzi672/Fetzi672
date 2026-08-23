package fac.loader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class RootOps {
    public static final String TARGET_PACKAGE="com.cocfz.com.freescript";
    private RootOps() {}

    public static boolean hasRoot(){
        try{return run("id").contains("uid=0");}catch(Exception e){return false;}
    }

    public static boolean forceStopTarget(){
        try{run("am force-stop "+TARGET_PACKAGE);return true;}catch(Exception e){return false;}
    }

    private static String run(String command)throws Exception{
        Process p=Runtime.getRuntime().exec(new String[]{"su","-c",command});
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        InputStream in=p.getInputStream(); byte[] b=new byte[1024]; int n,total=0;
        while((n=in.read(b))>0 && total<8192){int take=Math.min(n,8192-total);out.write(b,0,take);total+=take;}
        in.close();
        InputStream err=p.getErrorStream();
        while((n=err.read(b))>0 && total<8192){int take=Math.min(n,8192-total);out.write(b,0,take);total+=take;}
        err.close();
        int rc=p.waitFor();
        String s=new String(out.toByteArray(),StandardCharsets.UTF_8);
        if(rc!=0)throw new IllegalStateException("su command failed: "+rc+" "+s);
        return s;
    }
}
