package fac.guard;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Base64;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * FAC Guard V15 runtime payload manager.
 *
 * The known-good original APK is embedded in the Guard APK as an AES-GCM
 * encrypted binary asset named fac_runtime.bin. It is never repacked or
 * re-signed: after decryption we verify both its exact APK SHA-256 and its
 * original signing certificate before root installs those exact plaintext
 * bytes as com.cocfz.com.freescript.
 */
public final class PayloadManager {
    private static final String ASSET="fac_runtime.bin";
    private static final byte[] MAGIC=new byte[]{'F','A','C','P','1','5','0','1'};
    private static final int NONCE_LEN=12;
    private static final long EXPECTED_SIZE=42104041L;
    private static final String EXPECTED_APK_SHA256="1f98bac15ee733a5be48a51ed132d1918ff185bbdcc6b85a71603d835e1ad935";
    private static final String EXPECTED_CERT_SHA256="9fb2fd402397312752fee5f7c08b5d65c7bbad437287a3e8236bd83c06ed2ecc";

    private static final String K1="TVDnfJ+qMATE";
    private static final String K2="qm/Sar12RcAZ";
    private static final String K3="AUFNhMPoacgu";
    private static final String K4="V4RYdYs=";

    private PayloadManager() {}

    public static boolean isExpectedRuntimeInstalled(Context c){
        try{
            PackageInfo pi=c.getPackageManager().getPackageInfo(RootOps.TARGET_PACKAGE,PackageManager.GET_SIGNATURES);
            return pi!=null && RootOps.TARGET_PACKAGE.equals(pi.packageName) && certMatches(pi.signatures);
        }catch(Exception e){return false;}
    }

    public static boolean isAnyRuntimeInstalled(Context c){
        try{c.getPackageManager().getPackageInfo(RootOps.TARGET_PACKAGE,0);return true;}catch(Exception e){return false;}
    }

    /** @return true when V15 had to install/restore the embedded runtime. */
    public static boolean ensureInstalled(Context c)throws Exception{
        if(isExpectedRuntimeInstalled(c))return false;
        if(isAnyRuntimeInstalled(c)){
            throw new SecurityException("Installed original package has an unexpected signing certificate. Remove the modified package before FAC can restore the original runtime.");
        }
        if(!RootOps.hasRoot())throw new SecurityException("Root access is required to install the protected runtime.");

        File outFile=new File(c.getCacheDir(),"fac_runtime_v15.apk");
        if(outFile.exists())outFile.delete();
        try{
            decryptAndVerify(c,outFile);
            verifyArchive(c,outFile);
            if(!RootOps.installPackage(outFile)){
                String why=RootOps.lastInstallError();
                throw new IOException(why.length()==0?"Root package installation failed.":why);
            }
            if(!isExpectedRuntimeInstalled(c))throw new SecurityException("Installed runtime signature verification failed.");
            return true;
        }finally{
            try{outFile.delete();}catch(Exception ignored){}
            RootOps.removeInstallTemp();
        }
    }

    private static void decryptAndVerify(Context c,File dst)throws Exception{
        InputStream raw=c.getAssets().open(ASSET);
        byte[] magic=new byte[MAGIC.length];
        readFully(raw,magic);
        if(!MessageDigest.isEqual(MAGIC,magic))throw new SecurityException("FAC payload header invalid.");
        byte[] nonce=new byte[NONCE_LEN];readFully(raw,nonce);

        byte[] key=Base64.decode(K1+K2+K3+K4,Base64.NO_WRAP);
        if(key.length!=32)throw new SecurityException("FAC payload key invalid.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));

        MessageDigest md=MessageDigest.getInstance("SHA-256");
        CipherInputStream in=new CipherInputStream(raw,cipher);
        FileOutputStream out=new FileOutputStream(dst,false);
        byte[] buf=new byte[64*1024];int n;long total=0;
        try{
            while((n=in.read(buf))!=-1){
                if(n==0)continue;
                total+=n;
                if(total>EXPECTED_SIZE)throw new SecurityException("FAC payload size invalid.");
                md.update(buf,0,n);out.write(buf,0,n);
            }
            out.flush();out.getFD().sync();
        }finally{
            try{in.close();}catch(Exception ignored){}
            try{out.close();}catch(Exception ignored){}
        }
        if(total!=EXPECTED_SIZE)throw new SecurityException("FAC payload size invalid.");
        String digest=hex(md.digest());
        if(!EXPECTED_APK_SHA256.equals(digest))throw new SecurityException("FAC payload SHA-256 mismatch.");
    }

    private static void verifyArchive(Context c,File apk)throws Exception{
        PackageInfo pi=c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNATURES);
        if(pi==null || !RootOps.TARGET_PACKAGE.equals(pi.packageName))throw new SecurityException("FAC payload package identity invalid.");
        if(!certMatches(pi.signatures))throw new SecurityException("FAC payload signing certificate invalid.");
    }

    private static boolean certMatches(Signature[] signatures)throws Exception{
        if(signatures==null||signatures.length!=1)return false;
        byte[] d=MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
        return EXPECTED_CERT_SHA256.equals(hex(d));
    }

    private static void readFully(InputStream in,byte[] b)throws IOException{
        int off=0;while(off<b.length){int n=in.read(b,off,b.length-off);if(n<0)throw new EOFException("FAC payload truncated.");off+=n;}
    }

    private static String hex(byte[] d){
        StringBuilder s=new StringBuilder(d.length*2);
        for(byte b:d)s.append(String.format(Locale.US,"%02x",b&255));
        return s.toString();
    }
}
