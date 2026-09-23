package com.example.lms.infra.exec;

import org.springframework.core.task.TaskDecorator;

/**
 * Spring {@link TaskDecorator} that propagates MDC + GuardContext(ThreadLocal)
 * into @Async and other Spring-managed thread pools.
 */
public class ContextAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return ContextPropagation.wrap(runnable);
    }
}
