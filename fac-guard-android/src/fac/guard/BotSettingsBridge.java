package fac.guard;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Transactional bridge between the native Dear ImGui editor and
 * /storage/emulated/0/uix/zh1.txt. Persistence keys remain untouched; only
 * English labels/category metadata from the FAC schema are exposed to ImGui.
 */
public final class BotSettingsBridge {
    private static final File CONFIG=new File("/storage/emulated/0/uix/zh1.txt");
    private static final int MAX=512*1024;
    private static volatile String loadedHash="";
    private static volatile String lastError="";

    private BotSettingsBridge() {}

    public static synchronized String loadProtocol(Context c){
        try{
            byte[] configBytes=readFile(CONFIG);
            loadedHash=sha256(configBytes);
            JSONObject document=new JSONObject(new String(configBytes,StandardCharsets.UTF_8));
            JSONArray schema=new JSONArray(readAsset(c,"fac/bot_config_schema.json"));
            StringBuilder out=new StringBuilder(schema.length()*128);
            for(int i=0;i<schema.length();i++){
                JSONObject f=schema.getJSONObject(i);
                String key=f.getString("key");
                String type=f.getString("type");
                String category=f.getString("category");
                String label=f.getString("label");
                String value=document.optString(key,"");
                JSONArray a=f.optJSONArray("options");
                StringBuilder opts=new StringBuilder();
                if(a!=null){
                    for(int j=0;j<a.length();j++){
                        if(j>0)opts.append('\u001f');
                        opts.append(a.optString(j,""));
                    }
                }
                out.append(enc(category)).append('\t')
                   .append(enc(label)).append('\t')
                   .append(type).append('\t')
                   .append(enc(key)).append('\t')
                   .append(enc(value)).append('\t')
                   .append(enc(opts.toString())).append('\n');
            }
            lastError="";
            return out.toString();
        }catch(Exception e){
            lastError=safe(e,"Bot settings could not be loaded.");
            loadedHash="";
            return "";
        }
    }

    public static synchronized boolean saveProtocol(Context c,String protocol){
        try{
            if(protocol==null)throw new IOException("No settings data returned by ImGui.");
            byte[] fresh=readFile(CONFIG);
            String freshHash=sha256(fresh);
            if(loadedHash.length()==0||!loadedHash.equals(freshHash))
                throw new IOException("Bot settings changed externally. Reopen FAC Settings before saving.");

            JSONObject document=new JSONObject(new String(fresh,StandardCharsets.UTF_8));
            String[] lines=protocol.split("\\n");
            for(String line:lines){
                if(line.length()==0)continue;
                String[] p=line.split("\\t",-1);
                if(p.length<6)continue;
                String type=p[2];
                String key=dec(p[3]);
                String value=dec(p[4]);
                validate(type,value);
                document.put(key,value);
            }

            byte[] next=document.toString().getBytes(StandardCharsets.UTF_8);
            if(next.length>MAX)throw new IOException("Result exceeds 512 KiB.");
            File dir=CONFIG.getParentFile();
            if(dir==null||!dir.isDirectory())throw new IOException("Bot settings directory is unavailable.");
            File tmp=new File(dir,"zh1.txt.fac-v15-2-"+UUID.randomUUID()+".tmp");
            File bak=new File(dir,"zh1.txt.fac-v15-2.bak");
            writeSync(tmp,next);
            if(bak.exists()&&!bak.delete())throw new IOException("Could not replace previous FAC backup.");
            if(!CONFIG.renameTo(bak)){tmp.delete();throw new IOException("Could not create transactional backup.");}
            if(!tmp.renameTo(CONFIG)){
                bak.renameTo(CONFIG);
                throw new IOException("Atomic settings replace failed.");
            }
            byte[] verify=readFile(CONFIG);
            if(!sha256(verify).equals(sha256(next))){
                CONFIG.delete();bak.renameTo(CONFIG);
                throw new IOException("Post-write SHA-256 check failed; rollback completed.");
            }
            bak.delete();
            loadedHash=sha256(verify);
            lastError="";
            return true;
        }catch(Exception e){
            lastError=safe(e,"Bot settings save failed.");
            return false;
        }
    }

    public static String lastError(){return lastError;}

    private static void validate(String type,String value)throws IOException{
        if("INTEGER".equals(type)&&value.trim().length()>0){
            try{Long.parseLong(value.trim());}catch(Exception e){throw new IOException("Invalid integer value: "+value);}
        }else if("DECIMAL".equals(type)&&value.trim().length()>0){
            try{Double.parseDouble(value.trim());}catch(Exception e){throw new IOException("Invalid decimal value: "+value);}
        }
    }

    private static String readAsset(Context c,String path)throws Exception{
        InputStream in=c.getAssets().open(path);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] b=new byte[8192];int n,total=0;
        while((n=in.read(b))>0){total+=n;if(total>1024*1024)throw new IOException("FAC schema too large.");out.write(b,0,n);}
        in.close();return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }

    private static byte[] readFile(File f)throws Exception{
        if(!f.isFile())throw new FileNotFoundException("Bot settings file not found: "+f.getAbsolutePath());
        if(f.length()>MAX)throw new IOException("Bot settings file exceeds 512 KiB.");
        FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] b=new byte[8192];int n,total=0;
        while((n=in.read(b))>0){total+=n;if(total>MAX)throw new IOException("Bot settings file exceeds 512 KiB.");out.write(b,0,n);}
        in.close();return out.toByteArray();
    }

    private static void writeSync(File f,byte[] data)throws Exception{
        FileOutputStream out=new FileOutputStream(f,false);out.write(data);out.flush();out.getFD().sync();out.close();
    }

    private static String sha256(byte[] d)throws Exception{
        byte[] h=MessageDigest.getInstance("SHA-256").digest(d);StringBuilder s=new StringBuilder(h.length*2);
        for(byte x:h)s.append(String.format(Locale.US,"%02x",x&255));return s.toString();
    }

    private static String enc(String s){return Base64.encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);}
    private static String dec(String s){return new String(Base64.decode(s,Base64.NO_WRAP),StandardCharsets.UTF_8);}
    private static String safe(Exception e,String fallback){String s=e.getMessage();return s==null||s.trim().length()==0?fallback:s;}
}
