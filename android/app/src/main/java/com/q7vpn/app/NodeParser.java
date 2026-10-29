package com.q7vpn.app;

import android.util.Base64;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class NodeParser {
    private NodeParser() {}
    public static final class ParsedNode {
        public final String uri, name, protocol, host;
        public final int port;
        ParsedNode(String u,String n,String p,String h,int po){uri=u;name=n;protocol=p;host=h;port=po;}
    }
    public static List<ParsedNode> parseText(String input) {
        Map<String,ParsedNode> out=new LinkedHashMap<>();
        if(input==null)return new ArrayList<>();
        String decoded=decodeSubscription(input.trim());
        for(String line:decoded.split("\\r?\\n")){
            String s=line.trim();
            if(s.isEmpty()||s.startsWith("#")||s.startsWith("//"))continue;
            ParsedNode n=parseUri(s); if(n!=null)out.put(n.uri,n);
        }
        Matcher blocks=Pattern.compile("(?ms)^\\s*-\\s*name\\s*:\\s*['\"]?([^\\r\\n'\"]+)['\"]?\\s*\\r?\\n(.*?)(?=^\\s*-\\s*name\\s*:|\\z)").matcher(decoded);
        while(blocks.find()){
            String name=blocks.group(1).trim(), b=blocks.group(2);
            String type=field(b,"type"), host=field(b,"server"), ps=field(b,"port");
            try {
                int port=Integer.parseInt(ps);
                if(host.isEmpty()||port<1||port>65535)continue;
                String uri="clash://"+type.toLowerCase(Locale.ROOT)+"@"+host+":"+port+"#"+java.net.URLEncoder.encode(name,"UTF-8");
                out.put(uri,new ParsedNode(uri,name,type.toUpperCase(Locale.ROOT),host,port));
            } catch(Exception ignored) {}
        }
        return new ArrayList<>(out.values());
    }
    private static String field(String b,String k){
        Matcher m=Pattern.compile("(?m)^\\s*"+Pattern.quote(k)+"\\s*:\\s*['\"]?([^\\r\\n,'\"]+)").matcher(b);
        return m.find()?m.group(1).trim():"";
    }
    private static String decodeSubscription(String s){
        String t=s.replaceAll("\\s+","");
        if(t.matches("[A-Za-z0-9+/=_-]+")&&t.length()>=16){
            try{
                String d=new String(Base64.decode(t,Base64.DEFAULT|Base64.URL_SAFE),StandardCharsets.UTF_8);
                if(d.contains("://")||d.contains("proxies:"))return d;
            }catch(Exception ignored){}
        }
        return s;
    }
    public static ParsedNode parseUri(String raw){
        try{
            String s=raw.trim();
            if(s.startsWith("vmess://"))return parseVmess(s);
            if(!(s.startsWith("vless://")||s.startsWith("trojan://")||s.startsWith("ss://")))return null;
            URI u=URI.create(s); String h=u.getHost(); int p=u.getPort();
            if(h==null||p<1||p>65535)return null;
            String proto=s.substring(0,s.indexOf("://")).toUpperCase(Locale.ROOT);
            String name=u.getFragment()==null?h:URLDecoder.decode(u.getFragment(),"UTF-8");
            return new ParsedNode(s,name,proto,h,p);
        }catch(Exception e){return null;}
    }
    private static ParsedNode parseVmess(String raw){
        try{
            String p=raw.substring(8); int hash=p.indexOf('#'); if(hash>=0)p=p.substring(0,hash);
            JSONObject j=new JSONObject(new String(Base64.decode(p,Base64.DEFAULT|Base64.URL_SAFE),StandardCharsets.UTF_8));
            String h=j.optString("add",""); int port=Integer.parseInt(j.optString("port","0"));
            String name=j.optString("ps",h);
            if(h.isEmpty()||port<1||port>65535)return null;
            return new ParsedNode(raw,name,"VMESS",h,port);
        }catch(Exception e){return null;}
    }
}
