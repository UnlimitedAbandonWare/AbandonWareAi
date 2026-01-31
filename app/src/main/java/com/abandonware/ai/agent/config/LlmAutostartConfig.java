package com.abandonware.ai.agent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Arrays;

@Configuration
@ConditionalOnProperty(prefix = "llm", name = "autostart", havingValue = "true", matchIfMissing = false)
public class LlmAutostartConfig implements ApplicationRunner {
    private final ApplicationContext ctx;
    private final boolean preload;

    public LlmAutostartConfig(ApplicationContext ctx, @Value("${llm.preload:true}") boolean preload) {
        this.ctx = ctx;
        this.preload = preload;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Try several common bean names to avoid hard dependency
        String[] candidates = new String[]{"llamaCppLocalLLMService","llmService","localLlmService","llamaService"};
        Object bean = null;
        for (String name : candidates) {
            if (ctx.containsBean(name)) { bean = ctx.getBean(name); break; }
        }
        if (bean == null) return;

        if (preload) {
            // attempt to call ensureModel() if present
            tryInvoke(bean, "ensureModel");
        }
        // light warmup
        tryInvoke(bean, "warmup");
        if (!tryInvoke(bean, "generate", String.class, " ok")) {
            // try generic method names
            tryInvoke(bean, "infer", String.class, " ok");
        }
    }

    private boolean tryInvoke(Object bean, String methodName, Class<?>... paramTypesAndArgs) {
        try {
            Class<?>[] paramTypes = null;
            Object[] args = null;
            if (paramTypesAndArgs != null && paramTypesAndArgs.length > 0) {
                int n = paramTypesAndArgs.length;
                paramTypes = new Class<?>[]{ paramTypesAndArgs[0] };
                args = new Object[]{ paramTypesAndArgs[1] };
            }
            Method m = (paramTypes==null) ? bean.getClass().getMethod(methodName) : bean.getClass().getMethod(methodName, paramTypes);
            m.setAccessible(true);
            if (args==null) m.invoke(bean); else m.invoke(bean, args);
            return true;
        } catch (Exception ignore) { return false; }
    }
}
