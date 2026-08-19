package fac.license.ui;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;
import fac.license.DeviceIdentity;
import fac.license.SessionHandoff;

public class LicenseActivity extends Activity {
    public static final String VERIFY_URL="https://fac.fetzi-ai.de/api/android/licenses/verify";
    public static final String DEVICES_URL="https://fac.fetzi-ai.de/api/android/licenses/devices";
    public static final String APP_VERSION="310.0";
    public static final String PREF="fac_license";
    private static final String KEY_ALIAS="fac_license_key_v1";
    private EditText keyInput;
    private TextView status;
    private Button verify, devices, settings;
    private volatile boolean busy;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);

        // V10 stays in the current package process. If the untouched runtime
        // internally relaunches the package launcher while this exact verified
        // process is still alive, SessionHandoff forwards directly to Splash.
        // A genuinely new process falls through and verifies the saved key
        // online again before the original runtime is opened.
        if(!getIntent().getBooleanExtra("status_only",false)
                && SessionHandoff.handleLauncher(this)) return;

        buildUi();
        String saved=loadKey();
        if(saved.length()>0){ keyInput.setText(saved); verifyKey(saved,true); }
    }

    private int dp(int n){ return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    private TextView text(String s,int sp){ TextView v=new TextView(this); v.setText(s); v.setTextColor(Color.WHITE); v.setTextSize(sp); return v; }
    private void buildUi(){
        ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(Color.rgb(20,20,24));
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(24),dp(28),dp(24),dp(28)); scroll.addView(root,new ScrollView.LayoutParams(-1,-2));
        try { ImageView logo=new ImageView(this); logo.setAdjustViewBounds(true); logo.setMaxHeight(dp(150)); InputStream in=getAssets().open("fac/fac_logo_license.png"); logo.setImageBitmap(BitmapFactory.decodeStream(in)); in.close(); root.addView(logo,new LinearLayout.LayoutParams(-1,dp(150))); } catch(Exception ignored){}
        TextView title=text("FAC License",26); title.setGravity(Gravity.CENTER); title.setPadding(0,dp(12),0,dp(20)); root.addView(title);
        TextView hint=text("Enter your FAC license key",14); hint.setTextColor(Color.LTGRAY); root.addView(hint);
        keyInput=new EditText(this); keyInput.setSingleLine(true); keyInput.setTextColor(Color.WHITE); keyInput.setHintTextColor(Color.GRAY); keyInput.setHint("FACWEEK-XXXX-XXXX-XXXX"); keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); keyInput.setTypeface(android.graphics.Typeface.MONOSPACE); root.addView(keyInput,new LinearLayout.LayoutParams(-1,dp(54)));
        verify=new Button(this); verify.setText("VERIFY"); verify.setOnClickListener(v->verifyKey(keyInput.getText().toString().trim(),false)); root.addView(verify,new LinearLayout.LayoutParams(-1,dp(54)));
        status=text("Not verified",15); status.setPadding(0,dp(16),0,dp(12)); status.setTextColor(Color.LTGRAY); root.addView(status);
        devices=new Button(this); devices.setText("SHOW DEVICES"); devices.setEnabled(false); devices.setOnClickListener(v->showDevices()); root.addView(devices);
        settings=new Button(this); settings.setText("BOT SETTINGS"); settings.setEnabled(false); settings.setOnClickListener(v->startActivity(new Intent(this,BotSettingsActivity.class))); root.addView(settings);
        TextView note=text("After successful verification FAC opens the untouched original runtime directly without restarting the app process.",12); note.setTextColor(Color.GRAY); note.setPadding(0,dp(18),0,0); root.addView(note);
        setContentView(scroll);
    }

    private void setBusy(final boolean b){ busy=b; runOnUiThread(()->{ verify.setEnabled(!b); keyInput.setEnabled(!b); }); }
    private void verifyKey(final String k, final boolean automatic){
        if(busy||k.length()<8)return;
        setBusy(true); status("Verifying...",Color.LTGRAY);
        new Thread(()->{
            try {
                JSONObject req=baseRequest(k); HttpResult r=post(VERIFY_URL,req);
                if(r.code==200){
                    JSONObject o=new JSONObject(r.body);
                    if(o.optBoolean("verified",false)){
                        String exp=o.optString("licenseExpiresAtUtc","");
                        String srv=o.optString("serverTimeUtc","");
                        long expEpoch=parseUtc(exp), srvEpoch=parseUtc(srv);
                        if(expEpoch<=0 || srvEpoch<=0 || expEpoch<=srvEpoch) throw new IOException("invalid server time/expiry");
                        int bound=o.optInt("devicesBound",-1), limit=o.optInt("devicesLimit",-1);
                        if(bound<0 || limit<=0 || bound>limit) throw new IOException("invalid device counters");
                        saveKey(k);
                        long anchor=SystemClock.elapsedRealtime();
                        boolean persisted=getSharedPreferences(PREF,0).edit()
                            .putBoolean("verified",true)
                            .putString("expiry",exp)
                            .putLong("expiry_epoch_ms",expEpoch)
                            .putLong("server_epoch_ms",srvEpoch)
                            .putLong("verify_elapsed_ms",anchor)
                            .putInt("bound",bound).putInt("limit",limit).commit();
                        if(!persisted) throw new IOException("could not persist license state");
                        runOnUiThread(()->{
                            status.setTextColor(Color.rgb(80,220,120));
                            status.setText("License active until "+formatDate(expEpoch)+" • "+bound+"/"+limit+" devices");
                            devices.setEnabled(true); settings.setEnabled(true);
                        });
                        if(!getIntent().getBooleanExtra("status_only",false)){
                            status("Verified. Opening original FAC runtime...",Color.rgb(80,220,120));
                            SessionHandoff.activateAfterVerify(this);
                        }
                        return;
                    }
                }
                invalidateLocal();
                status("License invalid, expired, revoked, or device limit reached.",Color.rgb(255,90,90));
            } catch(Exception e){
                invalidateLocal();
                status("Server unreachable or response invalid. Try again later.",Color.rgb(255,90,90));
            } finally { setBusy(false); }
        }).start();
    }

    private void invalidateLocal(){
        SessionHandoff.clearRuntime(this);
        getSharedPreferences(PREF,0).edit().putBoolean("verified",false).commit();
    }

    private JSONObject baseRequest(String k) throws Exception { JSONObject o=new JSONObject(); o.put("licenseKey",k); o.put("deviceId",DeviceIdentity.get(this)); o.put("appVersion",APP_VERSION); return o; }
    private void showDevices(){
        final String k=keyInput.getText().toString().trim();
        new Thread(()->{
            try{
                HttpResult r=post(DEVICES_URL,baseRequest(k)); if(r.code!=200) throw new IOException();
                JSONObject o=new JSONObject(r.body); JSONArray a=o.optJSONArray("devices"); StringBuilder sb=new StringBuilder();
                if(a!=null) for(int i=0;i<a.length();i++){ JSONObject d=a.getJSONObject(i); sb.append("Device ").append(d.optInt("index",i+1)); if(d.optBoolean("current")) sb.append(" (this device)"); sb.append("\nBound: ").append(d.optString("boundAtUtc","—")); sb.append("\nLast seen: ").append(d.optString("lastSeenUtc","—")).append("\n\n"); }
                final String msg=sb.length()==0?"No devices returned.":sb.toString();
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Licensed devices").setMessage(msg).setPositiveButton("OK",null).show());
            }catch(Exception e){ status("Could not load device list.",Color.rgb(255,90,90)); }
        }).start();
    }
    private void status(final String s,final int c){ runOnUiThread(()->{status.setText(s);status.setTextColor(c);}); }

    private static class HttpResult{int code;String body;HttpResult(int c,String b){code=c;body=b;}}
    private HttpResult post(String u,JSONObject j)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection(); c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("Accept","application/json");
        byte[] d=j.toString().getBytes(StandardCharsets.UTF_8); if(d.length>4096)throw new IOException(); c.setFixedLengthStreamingMode(d.length); OutputStream os=c.getOutputStream();os.write(d);os.close();
        int code=c.getResponseCode(); InputStream in=code>=200&&code<400?c.getInputStream():c.getErrorStream(); ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(in!=null){byte[] buf=new byte[1024];int n,total=0;while((n=in.read(buf))>0&&total<4096){int take=Math.min(n,4096-total);out.write(buf,0,take);total+=take;if(total>=4096)break;}in.close();}
        c.disconnect(); return new HttpResult(code,new String(out.toByteArray(),StandardCharsets.UTF_8));
    }

    public static long parseUtc(String s){
        if(s==null)return -1; s=s.trim(); if(s.length()==0)return -1;
        try{
            if(s.endsWith("Z") && s.indexOf('.')>0){ int dot=s.indexOf('.'); s=s.substring(0,dot)+"Z"; }
            SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US); f.setLenient(false); f.setTimeZone(TimeZone.getTimeZone("UTC")); Date d=f.parse(s); return d==null?-1:d.getTime();
        }catch(Exception e){return -1;}
    }
    private String formatDate(long epoch){ try{return new SimpleDateFormat("dd.MM.yyyy",Locale.US).format(new Date(epoch));}catch(Exception e){return "—";} }

    private void saveKey(String key){
        if(Build.VERSION.SDK_INT<23)return;
        try{
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
            if(!ks.containsAlias(KEY_ALIAS)){ KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore"); kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); kg.generateKey(); }
            SecretKey sk=(SecretKey)ks.getKey(KEY_ALIAS,null); Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");ci.init(Cipher.ENCRYPT_MODE,sk);byte[] enc=ci.doFinal(key.getBytes(StandardCharsets.UTF_8));
            String blob=Base64.encodeToString(ci.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(enc,Base64.NO_WRAP);getSharedPreferences(PREF,0).edit().putString("key_blob",blob).commit();
        }catch(Exception ignored){}
    }
    private String loadKey(){
        if(Build.VERSION.SDK_INT<23)return "";
        try{
            String blob=getSharedPreferences(PREF,0).getString("key_blob","");if(blob.length()==0)return "";String[] p=blob.split(":",2); if(p.length!=2)return "";
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);SecretKey sk=(SecretKey)ks.getKey(KEY_ALIAS,null);if(sk==null)return "";
            Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");ci.init(Cipher.DECRYPT_MODE,sk,new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));return new String(ci.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }catch(Exception e){return "";}
    }
}
