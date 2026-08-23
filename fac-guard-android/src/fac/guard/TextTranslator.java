package fac.guard;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local, deterministic translation/sanitization for text exposed by the protected app. */
public final class TextTranslator {
    private final LinkedHashMap<String,String> exact=new LinkedHashMap<>();
    private static final Pattern ASCII_TOKEN=Pattern.compile("[#A-Za-z0-9_.:/%+\\-]{2,}");

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
        String low=s.toLowerCase(Locale.US);

        // Never reproduce legacy Aiwan/cocfz branding in the FAC overlay.
        if(isLegacyBrandText(s)){
            if(low.contains("http")||low.contains(".com")||s.contains("官网")||s.contains("网站"))return "FAC Support";
            if(s.contains("账号"))return "FAC Account";
            if(s.contains("密码"))return "FAC Password";
            if(s.contains("辅助"))return "FAC CoC Assistant";
            return "FAC";
        }

        String hit=exact.get(s);if(hit!=null&&hit.trim().length()>0)return sanitizeBrand(hit.trim());

        // High-frequency runtime/script messages that often come from compiled Lua.
        if(s.equals("开始运行"))return "Start";
        if(s.equals("停止运行"))return "Stop";
        if(s.equals("暂停运行"))return "Pause";
        if(s.equals("继续运行")||s.equals("恢复运行"))return "Resume";
        if(s.equals("重新运行")||s.equals("重启运行"))return "Restart";
        if(s.equals("运行中")||s.equals("正在运行"))return "Running";
        if(s.equals("已停止")||s.equals("脚本已停止"))return "Stopped";
        if(s.equals("确定")||s.equals("确认"))return "Confirm";
        if(s.equals("取消"))return "Cancel";
        if(s.equals("保存"))return "Save";
        if(s.equals("关闭"))return "Close";
        if(s.equals("返回")||s.equals("←返回"))return "Back";
        if(s.equals("设置"))return "Settings";
        if(s.equals("刷新"))return "Refresh";
        if(s.equals("退出"))return "Exit";
        if(s.equals("秒"))return "s";
        if(s.equals("分钟"))return "min";
        if(s.startsWith("首页设置")||s.startsWith("首页")||s.startsWith("主页"))return "Home";
        if(s.startsWith("账号设置")||s.startsWith("账号"))return "Account";
        if(s.startsWith("特殊功能"))return "Special";
        if(s.contains("自动运行倒计时")&&s.contains("停止"))return "Auto-run countdown stopped";
        if(s.contains("自动运行倒计时"))return "Auto-run countdown";

        if(!containsCjk(s))return sanitizeBrand(s);

        // Semantic fallback for previously unseen script notifications. This hides
        // every Han string while retaining useful runtime meaning and ASCII values.
        String base;
        if(s.contains("失败")||s.contains("错误")||s.contains("异常"))base="Operation failed";
        else if(s.contains("成功"))base="Completed successfully";
        else if(s.contains("完成"))base="Completed";
        else if(s.contains("保存"))base="Settings saved";
        else if(s.contains("登录"))base="Login";
        else if(s.contains("连接")||s.contains("联网"))base="Connecting";
        else if(s.contains("网络"))base="Network status";
        else if(s.contains("加载"))base="Loading";
        else if(s.contains("下载"))base="Downloading";
        else if(s.contains("更新"))base="Updating";
        else if(s.contains("检测")||s.contains("检查")||s.contains("校验"))base="Checking";
        else if(s.contains("等待"))base="Waiting";
        else if(s.contains("启动")||s.contains("开始"))base="Starting";
        else if(s.contains("停止")||s.contains("结束"))base="Stopped";
        else if(s.contains("运行"))base="Running";
        else if(s.contains("切换"))base="Switching";
        else if(s.contains("攻击")||s.contains("进攻"))base="Attack";
        else if(s.contains("训练")||s.contains("造兵"))base="Training";
        else if(s.contains("升级"))base="Upgrade";
        else if(s.contains("搜索")||s.contains("搜寻"))base="Searching";
        else if(s.contains("资源"))base="Resources";
        else if(s.contains("部落"))base="Clan";
        else if(s.contains("设置"))base="Settings";
        else base=clickable?"FAC Action":"FAC Message";

        String tokens=asciiTokens(s);
        return tokens.length()==0?base:(base+" • "+tokens);
    }

    public static boolean shouldMask(String raw){
        if(raw==null||raw.trim().length()==0)return false;
        return containsCjk(raw)||isLegacyBrandText(raw);
    }

    public static boolean isLegacyBrandText(String s){
        if(s==null)return false;
        String low=s.toLowerCase(Locale.US);
        return s.contains("爱玩")||s.contains("爱琦")||low.contains("aiwan")||low.contains("awfuzhu")||low.contains("cocfz.com")||low.contains("aiwanmianfei");
    }

    private static String asciiTokens(String s){
        Matcher m=ASCII_TOKEN.matcher(s);StringBuilder out=new StringBuilder();
        while(m.find()&&out.length()<72){String x=m.group();if(x.equalsIgnoreCase("http"))continue;if(out.length()>0)out.append(' ');out.append(sanitizeBrand(x));}
        return out.toString();
    }

    private static String sanitizeBrand(String s){
        return s.replaceAll("(?i)aiwan[^\\s]*","FAC")
                .replaceAll("(?i)aiwanmianfei\\.com(?::\\d+)?","FAC Support")
                .replaceAll("(?i)awfuzhu\\.com(?::\\d+)?","FAC Support")
                .replaceAll("(?i)cocfz\\.com(?::\\d+)?","FAC Support")
                .replace("爱玩","FAC").replace("爱琦","FAC");
    }

    public static boolean containsCjk(String s){
        if(s==null)return false;
        for(int i=0;i<s.length();){
            int cp=s.codePointAt(i);i+=Character.charCount(cp);
            Character.UnicodeScript sc=Character.UnicodeScript.of(cp);
            if(sc==Character.UnicodeScript.HAN)return true;
        }
        return false;
    }
}
