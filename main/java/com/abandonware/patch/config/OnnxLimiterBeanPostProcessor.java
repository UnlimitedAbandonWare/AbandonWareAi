package com.abandonware.patch.config;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.NameMatchMethodPointcutAdvisor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class OnnxLimiterBeanPostProcessor implements BeanPostProcessor {

    private final Semaphore limiter;

    public OnnxLimiterBeanPostProcessor(Semaphore onnxLimiter) {
        this.limiter = onnxLimiter;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!bean.getClass().getSimpleName().equals("OnnxCrossEncoderReranker")) return bean;
        ProxyFactory pf = new ProxyFactory(bean);
        NameMatchMethodPointcutAdvisor advisor = new NameMatchMethodPointcutAdvisor((MethodInterceptor) this::interceptRerank);
        advisor.setMappedName("rerank");
        pf.addAdvisor(advisor);
        return pf.getProxy();
    }

    private Object interceptRerank(MethodInvocation inv) throws Throwable {
        boolean acquired = false;
        try {
            acquired = limiter.tryAcquire(1, 30, TimeUnit.SECONDS);
            if (!acquired) return null; // budget fallback: let upstream handle
            return inv.proceed();
        } finally {
            if (acquired) limiter.release();
        }
    }
}
