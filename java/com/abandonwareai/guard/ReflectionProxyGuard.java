package com.abandonwareai.guard;

import org.springframework.stereotype.Component;

@Component
public class ReflectionProxyGuard {
    public boolean allow(Class<?> c){ return !c.getName().contains("java.lang.reflect.Proxy"); }

}