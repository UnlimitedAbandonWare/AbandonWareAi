package com.abandonware.ai.agent.infra.singleflight;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SingleFlight {
    String value(); // key expression or literal
    long ttlMillis() default 1000;
}