package com.abandonware.patch.infra.cache;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SingleFlight {
    String key() default "#p0"; // SpEL
}