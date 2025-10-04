package com.example.lms.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component              // ← Bean 이름은 클래스명 camelCase: reqLogInterceptor
public class ReqLogInterceptor implements HandlerInterceptor {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReqLogInterceptor.class);


    @Override
    public boolean preHandle(HttpServletRequest req,
                             HttpServletResponse res,
                             Object handler) {
        String fullUrl = req.getRequestURL()
                .append(req.getQueryString() != null ? "?" + req.getQueryString() : "")
                .toString();
        log.info("[HTTP-IN] {} {}", req.getMethod(), fullUrl);
        return true;
    }
}
