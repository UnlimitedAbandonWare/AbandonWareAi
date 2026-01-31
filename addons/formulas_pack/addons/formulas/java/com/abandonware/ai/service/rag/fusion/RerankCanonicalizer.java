package com.abandonware.ai.service.rag.fusion;

import java.net.*; import java.util.*;
public final class RerankCanonicalizer {
  private static final Set<String> DROP = Set.of("utm_source","utm_medium","utm_campaign","utm_term","utm_content","gclid","fbclid","ref");
  public String canonical(String raw){
    if(raw==null) return null;
    try{
      URI u=new URI(raw.trim());
      String scheme=u.getScheme()==null? "https":u.getScheme().toLowerCase(Locale.ROOT);
      String host=u.getHost()==null? "":u.getHost().toLowerCase(Locale.ROOT);
      String path=(u.getPath()==null? "":u.getPath()).replaceAll("/+$","");
      String q=u.getQuery();
      if(q!=null){
        List<String> kept=new ArrayList<>();
        for(String kv: q.split("&")){
          String[] p=kv.split("=",2); String key=p.length>0?p[0]:""; if(!DROP.contains(key)) kept.add(kv);
        }
        q = kept.isEmpty()? null : String.join("&", kept);
      }
      return new URI(scheme, u.getUserInfo(), host, u.getPort(), path.isEmpty()? "/":path, q, null).toString();
    }catch(Exception e){ return raw; }
  }
}
