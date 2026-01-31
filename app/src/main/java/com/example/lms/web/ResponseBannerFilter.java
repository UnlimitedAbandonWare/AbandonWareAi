package com.example.lms.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

public class ResponseBannerFilter implements Filter {
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;
    Object lvl = req.getAttribute("nova.errorbreak.level");
    if (lvl != null) {
      res.setHeader("X-Nova-ErrorBreak", String.valueOf(lvl));
    }
    chain.doFilter(request, response);
  }
}