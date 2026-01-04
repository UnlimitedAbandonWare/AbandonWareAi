package com.abandonware.ai.util;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

public final class UrlCanonicalizer {
    private static final Set<String> DROP_PARAMS = new HashSet<>(Arrays.asList(
            "gclid","fbclid","ref"
    ));
    private static final Pattern UTM = Pattern.compile("^utm_.*", Pattern.CASE_INSENSITIVE);
    private UrlCanonicalizer(){}

    public static String canonicalKey(String url){
        if (url == null) return null;
        try{
            URI u = URI.create(url);
            String scheme = (u.getScheme()==null?"http":u.getScheme().toLowerCase(Locale.ROOT));
            String host = u.getHost()==null? "": u.getHost().toLowerCase(Locale.ROOT);
            int port = u.getPort();
            String authority = host;
            if (port != -1 && !((scheme.equals("http") && port==80) || (scheme.equals("https") && port==443))) {
                authority = host + ":" + port;
            }
            String path = (u.getPath()==null || u.getPath().isBlank()) ? "/" : u.getPath();
            // scrub query params
            Map<String,String> kept = new LinkedHashMap<>();
            String q = u.getQuery();
            if (q != null && !q.isBlank()){
                for (String kv : q.split("&")){
                    if (kv.isBlank()) continue;
                    String[] parts = kv.split("=",2);
                    String k = parts[0];
                    if (k == null) continue;
                    String kl = k.toLowerCase(Locale.ROOT);
                    if (DROP_PARAMS.contains(kl)) continue;
                    if (UTM.matcher(kl).matches()) continue;
                    if (kl.startsWith("ref_")) continue;
                    kept.put(k, parts.length>1?parts[1]:"");
                }
            }
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String,String> e : kept.entrySet()){
                if (query.length()>0) query.append("&");
                query.append(e.getKey()).append("=").append(e.getValue());
            }
            String result = scheme + "://" + authority + path;
            if (query.length()>0) result += "?" + query;
            // drop fragment
            if (result.endsWith("/")) result = result.substring(0, result.length()-1);
            return result;
        }catch(Exception e){
            return url;
        }
    }
}