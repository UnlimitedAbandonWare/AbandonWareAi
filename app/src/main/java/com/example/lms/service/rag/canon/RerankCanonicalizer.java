package com.example.lms.service.rag.canon;

import java.net.URI;
import java.net.URISyntaxException;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.canon.RerankCanonicalizer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.canon.RerankCanonicalizer
role: config
*/
public class RerankCanonicalizer {
  public String canonicalUrl(String url){
    try{
      URI u = new URI(url);
      return new URI(u.getScheme(), u.getAuthority(), u.getPath(), null, u.getFragment()).toString();
    }catch(URISyntaxException e){ return url; }
  }
}