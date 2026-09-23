package com.abandonware.ai.agent;

import com.nova.protocol.config.NovaProtocolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentApplicationPlanLoaderBeanNamesTest {
    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    @Configuration(proxyBeanMethods = false)
    static class ConstructionSentinel {
        @Bean
        Object mustNeverBeConstructed() {
            CONSTRUCTIONS.incrementAndGet();
            throw new AssertionError("Definition-only test constructed a bean");
        }
    }

    @Test
    void novaFactoryAloneRegistersItsOwnPlanLoaderType() {
        var factory = registerDefinitions(false, false);
        assertNovaFactoryDefinition(factory);
        assertFalse(factory.containsBeanDefinition("agentPlanLoader"));
    }

    @ParameterizedTest(name = "Agent PlanLoader registered first: {0}")
    @ValueSource(booleans = {false, true})
    void novaFactoryAndAgentComponentKeepDistinctTypesAndNames(boolean agentFirst) {
        var factory = registerDefinitions(true, agentFirst);
        assertNovaFactoryDefinition(factory);
        assertEquals("com.abandonware.ai.agent.service.plan.PlanLoader",
                factory.getBeanDefinition("agentPlanLoader").getBeanClassName());
        assertNull(factory.getSingleton("agentPlanLoader"));
    }

    private static void assertNovaFactoryDefinition(DefaultListableBeanFactory factory) {
        var definition = assertInstanceOf(AnnotatedBeanDefinition.class, factory.getBeanDefinition("planLoader"));
        var method = definition.getFactoryMethodMetadata();
        assertNotNull(method);
        assertEquals("planLoader", method.getMethodName());
        assertEquals("com.nova.protocol.plan.PlanLoader", method.getReturnTypeName());
        assertNull(factory.getSingleton("planLoader"));
        assertNull(factory.getSingleton("novaProtocolConfig"));
        assertTrue(factory.containsBeanDefinition("mustNeverBeConstructed"));
        assertEquals(0, CONSTRUCTIONS.get());
    }

    private static DefaultListableBeanFactory registerDefinitions(boolean includeAgent, boolean agentFirst) {
        CONSTRUCTIONS.set(0);
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("definition-test", Map.of(
                "spring.main.web-application-type", "none",
                "spring.main.allow-bean-definition-overriding", "false")));
        var factory = new DefaultListableBeanFactory();
        factory.setAllowBeanDefinitionOverriding(false);
        var context = new GenericApplicationContext(factory);
        context.setEnvironment(environment);
        var reader = new AnnotatedBeanDefinitionReader(context, environment);
        var scanner = new ClassPathBeanDefinitionScanner(context, false, environment);
        scanner.addIncludeFilter((metadata, readerFactory) -> metadata.getClassMetadata().getClassName()
                .equals("com.abandonware.ai.agent.service.plan.PlanLoader"));
        reader.register(ConstructionSentinel.class);
        if (includeAgent && agentFirst) scanner.scan("com.abandonware.ai.agent.service.plan");
        reader.register(NovaProtocolConfig.class);
        if (includeAgent && !agentFirst) scanner.scan("com.abandonware.ai.agent.service.plan");
        var processor = new ConfigurationClassPostProcessor();
        processor.setEnvironment(environment);
        processor.setResourceLoader(context);
        processor.setBeanClassLoader(context.getClassLoader());
        processor.postProcessBeanDefinitionRegistry(factory);
        return factory;
    }
}
