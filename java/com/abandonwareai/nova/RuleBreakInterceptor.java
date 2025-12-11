package com.abandonwareai.nova;

import org.springframework.stereotype.Component;

@Component
public class RuleBreakInterceptor {
    public boolean hasToken(String header){ return header!=null && !header.isEmpty(); }

}