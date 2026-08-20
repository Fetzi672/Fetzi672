package fac.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class LicenseStore {
    private static final String PREF="fac_guard_v14";
    private static final String KEY_ALIAS="fac_guard_license_key_v14";
    private LicenseStore() {}

    private static SharedPreferences p(Context c){return c.getSharedPreferences(PREF,0);}

    public static synchronized void saveKey(Context c,String key)throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(KEY_ALIAS)){
            KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            kg.generateKey();
        }
        SecretKey sk=(SecretKey)ks.getKey(KEY_ALIAS,null);
        Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");ci.init(Cipher.ENCRYPT_MODE,sk);
        byte[] enc=ci.doFinal(key.getBytes(StandardCharsets.UTF_8));
        String blob=Base64.encodeToString(ci.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(enc,Base64.NO_WRAP);
        if(!p(c).edit().putString("key_blob",blob).commit())throw new IllegalStateException("could not store key");
    }

    public static synchronized String loadKey(Context c){
        try{
            String blob=p(c).getString("key_blob","");if(blob.length()==0)return "";
            String[] x=blob.split(":",2);if(x.length!=2)return "";
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
            SecretKey sk=(SecretKey)ks.getKey(KEY_ALIAS,null);if(sk==null)return "";
            Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE,sk,new GCMParameterSpec(128,Base64.decode(x[0],Base64.NO_WRAP)));
            return new String(ci.doFinal(Base64.decode(x[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }catch(Exception e){return "";}
    }

    public static boolean saveVerification(Context c,LicenseApi.Result r){
        return p(c).edit()
            .putBoolean("verified",true)
            .putString("expiry",r.expiryUtc)
            .putLong("expiry_epoch_ms",r.expiryEpochMs)
            .putLong("server_epoch_ms",r.serverEpochMs)
            .putLong("verify_elapsed_ms",SystemClock.elapsedRealtime())
            .putInt("bound",r.devicesBound)
            .putInt("limit",r.devicesLimit)
            .commit();
    }

    public static boolean isLocallyValid(Context c){
        SharedPreferences s=p(c);
        if(!s.getBoolean("verified",false))return false;
        long expiry=s.getLong("expiry_epoch_ms",-1L),server=s.getLong("server_epoch_ms",-1L),anchor=s.getLong("verify_elapsed_ms",-1L);
        long now=SystemClock.elapsedRealtime();
        if(expiry<=0||server<=0||anchor<0||now<anchor)return false;
        return server+(now-anchor)<expiry;
    }

    public static long verificationElapsed(Context c){return p(c).getLong("verify_elapsed_ms",-1L);}
    public static int bound(Context c){return p(c).getInt("bound",-1);}
    public static int limit(Context c){return p(c).getInt("limit",-1);}
    public static String expiry(Context c){return p(c).getString("expiry","");}
    public static boolean isVerified(Context c){return p(c).getBoolean("verified",false);}

    public static boolean isArmed(Context c){return p(c).getBoolean("armed",true);}
    public static void setArmed(Context c,boolean armed){p(c).edit().putBoolean("armed",armed).commit();}

    public static boolean isSessionActive(Context c){return p(c).getBoolean("session_active",false);}
    public static void setSessionActive(Context c,boolean active){p(c).edit().putBoolean("session_active",active).commit();}

    public static void invalidate(Context c){
        p(c).edit().putBoolean("verified",false).putBoolean("session_active",false).commit();
    }

    public static void clearSession(Context c){p(c).edit().putBoolean("session_active",false).commit();}

    public static void setLastEvent(Context c,String text){p(c).edit().putString("last_event",text==null?"":text).commit();}
    public static String lastEvent(Context c){return p(c).getString("last_event","");}
}
