package com.q7vpn.app;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native form client for the original Q7VPN Flask account backend. */
public final class Q7AccountClient {
    private static final String BASE = "https://q7vpn.onrender.com";
    private final Context context;
    private String sessionCookie = "";

    public static final class Bootstrap { public String csrf="", captchaCode="", message=""; public int lockSeconds; }
    public static final class AuthResult { public boolean loggedIn, registered; public String message=""; public Bootstrap nextBootstrap; }
    public static final class RecoveryResult { public boolean success; public int expiresIn=300; public String csrf="", recoveryToken="", accountId="", username="", phone="", message=""; public Bootstrap nextBootstrap; }
    public static final class AccountInfo { public String csrf="", accountId="", username="", phone="", nodeCount="", message=""; }
    private static final class HttpResult { int status; String body="", location=""; }

    public Q7AccountClient(Context context) {
        this.context = context.getApplicationContext();
        try { sessionCookie = Q7SecureStore.read(this.context, "account_session_cookie"); }
        catch (Exception ignored) { sessionCookie = ""; }
    }
    public synchronized void clearSession() {
        sessionCookie = "";
        try { Q7SecureStore.write(context, "account_session_cookie", ""); } catch (Exception ignored) { }
    }
    private synchronized void persistCookie() {
        try { Q7SecureStore.write(context, "account_session_cookie", sessionCookie); } catch (Exception ignored) { }
    }
    public boolean isAuthenticated() {
        try { HttpResult r=request("GET","/dashboard",null); return r.status==200 && r.body.contains("CONTROL CENTER"); }
        catch (Exception ignored) { return false; }
    }

    public Bootstrap bootstrap(String mode) {
        Bootstrap b=new Bootstrap();
        try {
            HttpResult page=request("GET","/auth?mode="+enc(mode),null);
            b.csrf=extract(page.body,"name=[\\\"']csrf_token[\\\"'][^>]*value=[\\\"']([^\\\"']+)",1);
            b.lockSeconds=intExtract(page.body,"data-seconds=[\\\"'](\\d+)");
            b.message=flash(page.body);
            HttpResult image=request("GET","/captcha.svg?refresh=1",null);
            Matcher m=Pattern.compile("<text[^>]*>([A-Z0-9])</text>",Pattern.CASE_INSENSITIVE).matcher(image.body);
            StringBuilder code=new StringBuilder(); while(m.find()) code.append(m.group(1).toUpperCase());
            b.captchaCode=code.length()==4?code.toString():"";
            if(page.status>=400||image.status>=400) b.message="账户服务暂时不可用，请稍后重试。";
            else if(b.csrf.isEmpty()) b.message="无法获取安全令牌，请刷新后重试。";
        } catch(Exception e) { b.message="无法连接 Q7VPN 账户服务器，请检查网络。"; }
        return b;
    }

    public AuthResult submitAuth(String mode,String user,String password,String confirmPassword,String captcha,String phone,String question,String answer,String csrf) {
        AuthResult out=new AuthResult();
        try {
            Map<String,String> f=new LinkedHashMap<>();
            f.put("csrf_token",csrf); f.put("mode",mode); f.put("username",user); f.put("password",password); f.put("captcha",captcha);
            if("register".equals(mode)){f.put("phone",phone);f.put("confirm_password",confirmPassword);f.put("question",question);f.put("answer",answer);}
            HttpResult r=request("POST","/auth",f);
            if(r.status==302&&r.location!=null&&r.location.contains("/dashboard")){out.loggedIn=true;return out;}
            out.message=flash(r.body);
            out.registered="register".equals(mode)&&out.message.contains("注册成功");
            out.nextBootstrap=bootstrap(out.registered?"login":mode);
            if(out.message.isEmpty()) out.message=r.status==429?"请求过于频繁，请稍后再试。":"登录未完成，请检查信息和验证码后重试。";
        } catch(Exception e) { out.message="账户服务器连接失败，请检查网络后重试。"; out.nextBootstrap=bootstrap(mode); }
        return out;
    }

    public RecoveryResult beginRecovery(String user,String phone,String captcha,String csrf) {
        RecoveryResult out=new RecoveryResult();
        try {
            Map<String,String> f=new LinkedHashMap<>(); f.put("csrf_token",csrf); f.put("step","lookup");
            f.put("username",user); f.put("phone",phone); f.put("captcha",captcha);
            HttpResult r=request("POST","/recover",f);
            out.csrf=extract(r.body,"name=[\\\"']csrf_token[\\\"'][^>]*value=[\\\"']([^\\\"']+)",1);
            out.recoveryToken=extract(r.body,"id=[\\\"']recovery-token[\\\"'][^>]*>([^<]+)</",1);
            out.accountId=extract(r.body,"id=[\\\"']recovery-account-id[\\\"'][^>]*>([^<]+)</",1);
            out.username=extract(r.body,"id=[\\\"']recovery-username[\\\"'][^>]*>([^<]+)</",1);
            out.phone=extract(r.body,"id=[\\\"']recovery-phone[\\\"'][^>]*>([^<]+)</",1);
            out.expiresIn=intExtract(r.body,"id=[\\\"']recovery-expiry[\\\"'][^>]*data-seconds=[\\\"'](\\d+)");
            if(out.expiresIn<=0)out.expiresIn=300;
            out.message=flash(r.body); out.success=!out.recoveryToken.isEmpty()&&!out.csrf.isEmpty();
            if(!out.success){out.nextBootstrap=bootstrap("login");if(out.message.isEmpty())out.message="账号 ID、手机号或图片验证码不匹配。";}
        } catch(Exception e) { out.message="账户服务器连接失败，请检查网络后重试。"; out.nextBootstrap=bootstrap("login"); }
        return out;
    }
    public RecoveryResult completeRecovery(String token,String password,String confirmPassword,String csrf) {
        RecoveryResult out=new RecoveryResult(); out.csrf=csrf;
        try {
            Map<String,String> f=new LinkedHashMap<>();f.put("csrf_token",csrf);f.put("step","reset");
            f.put("recovery_token",token);f.put("new_password",password);f.put("confirm_password",confirmPassword);
            HttpResult r=request("POST","/recover",f);
            out.success=r.status==302&&r.location!=null&&r.location.contains("/auth");
            out.message=flash(r.body);
            if(out.message.isEmpty())out.message=out.success?"密码已重置，请重新登录。":"恢复凭证已过期或密码格式不正确。";
            if(!out.success)out.nextBootstrap=bootstrap("login");
        } catch(Exception e) { out.message="账户服务器连接失败，请重试。"; out.nextBootstrap=bootstrap("login"); }
        return out;
    }

    public AccountInfo fetchAccountInfo() {
        try {
            HttpResult r=request("GET","/security",null); if(r.status!=200)return null;
            AccountInfo a=new AccountInfo();
            a.csrf=extract(r.body,"name=[\\\"']csrf_token[\\\"'][^>]*value=[\\\"']([^\\\"']+)",1);
            a.accountId=extract(r.body,"<div class=[\\\"']account-meta[\\\"'].*?<h2>(.*?)</h2>",1);
            a.username=extract(r.body,"<p>@([^<]+)</p>",1);
            a.phone=extract(r.body,"<span>绑定手机号</span>\\s*<strong>(.*?)</strong>",1);
            a.nodeCount=extract(r.body,"<span>已保存节点</span>\\s*<strong>(.*?)</strong>",1);
            a.message=flash(r.body);
            return a.accountId.isEmpty()||a.username.isEmpty()?null:a;
        } catch(Exception ignored) { return null; }
    }
    public String changePassword(String csrf,String oldPassword,String newPassword,String confirmPassword) {
        try {
            Map<String,String> f=new LinkedHashMap<>();f.put("csrf_token",csrf);f.put("current_password",oldPassword);f.put("new_password",newPassword);f.put("confirm_password",confirmPassword);
            request("POST","/security/password",f);
            return flash(request("GET","/security",null).body);
        } catch(Exception e) { return "账户服务连接失败，密码状态尚未确认。"; }
    }
    public void logout() {
        try {
            String token=extract(request("GET","/security",null).body,"name=[\\\"']csrf_token[\\\"'][^>]*value=[\\\"']([^\\\"']+)",1);
            Map<String,String> f=new LinkedHashMap<>();f.put("csrf_token",token);request("POST","/logout",f);
        } catch(Exception ignored) { }
        clearSession();
    }

    private HttpResult request(String method,String path,Map<String,String> fields)throws Exception {
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(BASE+path).openConnection();c.setRequestMethod(method);
            c.setConnectTimeout(12000);c.setReadTimeout(15000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent","Q7VPN-Android/6");c.setRequestProperty("Accept","text/html, image/svg+xml, */*");c.setRequestProperty("Cache-Control","no-cache");
            String cookie; synchronized(this){cookie=sessionCookie;} if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);
            if("POST".equals(method)){
                c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
                c.setRequestProperty("Origin",BASE);c.setRequestProperty("Referer",BASE+"/auth");
                StringBuilder body=new StringBuilder();
                for(Map.Entry<String,String> e:fields.entrySet()){if(body.length()>0)body.append('&');body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));}
                try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            }
            HttpResult r=new HttpResult();r.status=c.getResponseCode();r.location=c.getHeaderField("Location");
            String setCookie=c.getHeaderField("Set-Cookie");
            if(setCookie!=null){int end=setCookie.indexOf(';');String value=end<0?setCookie:setCookie.substring(0,end);if(value.startsWith("session=")){synchronized(this){sessionCookie=value;persistCookie();}}}
            InputStream stream;try{stream=r.status>=400?c.getErrorStream():c.getInputStream();}catch(Exception ignored){stream=c.getErrorStream();}
            if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n,total=0;while((n=in.read(buffer))!=-1&&total<768*1024){int take=Math.min(n,768*1024-total);out.write(buffer,0,take);total+=take;}
                r.body=new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
            return r;
        } finally { if(c!=null)c.disconnect(); }
    }
    private static String enc(String s)throws Exception{return URLEncoder.encode(s==null?"":s,"UTF-8");}
    private static String extract(String source,String regex,int group){
        if(source==null)return "";Matcher m=Pattern.compile(regex,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(source);
        return m.find()?decode(strip(m.group(group))).trim():"";
    }
    private static int intExtract(String source,String regex){try{return Integer.parseInt(extract(source,regex,1));}catch(Exception ignored){return 0;}}
    private static String flash(String source){return extract(source,"<div class=[\\\"']flash[^\\\"']*[\\\"'][^>]*>(.*?)</div>",1);}
    private static String strip(String source){return source==null?"":source.replaceAll("<[^>]+>","").trim();}
    private static String decode(String source){return source==null?"":source.replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;", "\"").replace("&#39;","'").replace("&nbsp;"," ");}
}
