package web;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

public class RuleBreakInterceptor implements Filter {
  private final String header;
  private final String token;
  public static final String ATTR="rulebreak.enabled";

  public RuleBreakInterceptor(String header, String token){ this.header=header; this.token=token; }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
    throws IOException, ServletException {
    HttpServletRequest http = (HttpServletRequest) req;
    boolean enabled = token!=null && !token.isBlank() && token.equals(http.getHeader(header));
    http.setAttribute(ATTR, enabled);
    chain.doFilter(req, res);
  }
}