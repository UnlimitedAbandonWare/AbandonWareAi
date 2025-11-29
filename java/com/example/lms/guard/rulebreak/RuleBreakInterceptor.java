package com.example.lms.guard.rulebreak;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.example.lms.guard.rulebreak.RuleBreakContext;




@Component
public class RuleBreakInterceptor implements HandlerInterceptor {

  @Autowired RuleBreakEvaluator evaluator;
  

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    RuleBreakContext ctx = evaluator.evaluateFromHeaders(req);
    if (ctx != null && ctx.isValid()) {
        // RuleBreak context captured via Nova layer; no-op here to avoid type clash.
    }
    return true;
  }
}