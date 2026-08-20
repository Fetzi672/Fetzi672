package fac.guard;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local, deterministic translation/sanitization for text exposed by the protected app. */
public final class TextTranslator {
    private final LinkedHashMap<String,String> exact=new LinkedHashMap<>();

    public TextTranslator(Context c){
        try{
            BufferedReader r=new BufferedReader(new InputStreamReader(c.getAssets().open("fac/text_map.tsv"),StandardCharsets.UTF_8));
            String line;
            while((line=r.readLine())!=null){
                if(line.length()==0||line.charAt(0)=='#')continue;
                int p=line.indexOf('\t');if(p<=0)continue;
                exact.put(line.substring(0,p),line.substring(p+1));
            }
            r.close();
        }catch(Exception ignored){}
    }

    public String translate(String raw,boolean clickable){
        if(raw==null)return "";
        String s=raw.trim();if(s.length()==0)return "";

        // Brand and legacy-site suppression always wins over literal mappings.
        String low=s.toLowerCase(Locale.US);
        if(s.contains("爱玩")||s.contains("爱琦")||low.contains("aiwan")||low.contains("awfuzhu")||low.contains("cocfz.com")){
            if(low.contains("http")||low.contains(".com")||s.contains("官网"))return "FAC Support";
            if(s.contains("辅助"))return "FAC CoC Assistant";
            return "FAC";
        }

        String hit=exact.get(s);if(hit!=null&&hit.trim().length()>0)return sanitizeBrand(hit.trim());

        if(s.startsWith("首页设置")||s.startsWith("首页")||s.startsWith("主页"))return "Home";
        if(s.startsWith("账号设置")||s.startsWith("账号"))return "Account";
        if(s.startsWith("特殊功能"))return "Special";
        if(s.contains("自动运行倒计时")&&s.contains("停止"))return "Auto-run countdown stopped";
        if(s.contains("自动运行倒计时"))return "Auto-run countdown";
        if(s.equals("开始运行"))return "Start";
        if(s.equals("停止运行"))return "Stop";
        if(s.equals("暂停运行"))return "Pause";
        if(s.equals("继续运行")||s.equals("恢复运行"))return "Resume";
        if(s.equals("重新运行")||s.equals("重启运行"))return "Restart";
        if(s.equals("运行中")||s.equals("正在运行"))return "Running";
        if(s.equals("已停止")||s.equals("脚本已停止"))return "Stopped";
        if(s.equals("确定"))return "OK";
        if(s.equals("确认"))return "Confirm";
        if(s.equals("取消"))return "Cancel";
        if(s.equals("保存"))return "Save";
        if(s.equals("关闭"))return "Close";
        if(s.equals("返回")||s.equals("←返回"))return "Back";
        if(s.equals("设置"))return "Settings";
        if(s.equals("刷新"))return "Refresh";
        if(s.equals("退出"))return "Exit";
        if(s.equals("秒"))return "s";
        if(s.equals("分钟"))return "min";

        // Keep already-English/numeric runtime information, but never leak legacy brand strings.
        if(!containsCjk(s))return sanitizeBrand(s);

        // We intentionally never draw untranslated Han characters. Unknown custom Lua strings
        // receive a short neutral English label until an exact mapping is added to text_map.tsv.
        return clickable?"Action":"Status";
    }

    private static String sanitizeBrand(String s){
        String x=s.replaceAll("(?i)aiwan[^\\s]*","FAC")
                  .replaceAll("(?i)awfuzhu\\.com(?::\\d+)?","FAC Support")
                  .replaceAll("(?i)cocfz\\.com(?::\\d+)?","FAC Support");
        return x;
    }

    public static boolean containsCjk(String s){
        for(int i=0;i<s.length();){
            int cp=s.codePointAt(i);i+=Character.charCount(cp);
            Character.UnicodeScript sc=Character.UnicodeScript.of(cp);
            if(sc==Character.UnicodeScript.HAN)return true;
        }
        return false;
    }
}
