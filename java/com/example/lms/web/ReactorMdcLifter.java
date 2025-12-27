// src/main/java/com/example/lms/web/ReactorMdcLifter.java
package com.example.lms.web;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Bridges Reactor Context -> MDC via a global hook.
 */
@Configuration
public class ReactorMdcLifter {
    private static final Logger log = LoggerFactory.getLogger(ReactorMdcLifter.class);

    @PostConstruct
    public void hook() {
        try {
            Hooks.onEachOperator("mdcLifter", liftMdc());
            log.info("Reactor MDC lifter installed");
        } catch (Exception e) {
            log.warn("Failed to install Reactor MDC lifter", e);
        }
    }

    /**
     * Hooks.onEachOperator(String, Function<? super Publisher<Object>, ? extends Publisher<Object>>)
     * 요구 시그니처에 맞게 Object로 고정.
     */
    private static Function<? super Publisher<Object>, ? extends Publisher<Object>> liftMdc() {
        return Operators.<Object, Object>liftPublisher((pub, actual) -> new CoreSubscriber<Object>() {
            @Override
            public void onSubscribe(Subscription s) {
                actual.onSubscribe(s);
            }
            @Override
            public void onNext(Object o) {
                withMdc(actual.currentContext(), () -> actual.onNext(o));
            }
            @Override
            public void onError(Throwable t) {
                withMdc(actual.currentContext(), () -> actual.onError(t));
            }
            @Override
            public void onComplete() {
                withMdc(actual.currentContext(), actual::onComplete);
            }
            @Override
            public Context currentContext() {
                return actual.currentContext();
            }
        });
    }

    private static void withMdc(Context context, Runnable action) {
        Map<String, String> backup = MDC.getCopyOfContextMap();
        // 미리 키를 수집해두었다가 실행 후 제거
        Set<String> keys = context.stream()
                .map(e -> String.valueOf(e.getKey()))
                .collect(Collectors.toSet());
        try {
            context.stream().forEach(e ->
                    MDC.put(String.valueOf(e.getKey()), String.valueOf(e.getValue())));
            action.run();
        } finally {
            keys.forEach(MDC::remove);
            if (backup != null) MDC.setContextMap(backup);
            else MDC.clear();
        }
    }
}