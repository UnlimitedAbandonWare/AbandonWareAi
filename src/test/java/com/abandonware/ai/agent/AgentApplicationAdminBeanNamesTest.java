package com.abandonware.ai.agent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentApplicationAdminBeanNamesTest {
    private static final String LMS_ADMIN = "com.example.lms.api.AdminController";
    private static final String AGENT_ADMIN = "com.abandonware.ai.agent.web.AdminController";

    @ParameterizedTest(name = "distinct admin bean names with agent package first: {0}")
    @ValueSource(booleans = {false, true})
    void bothAdminControllersRegisterWithoutConstructingServices(boolean agentFirst) {
        var factory = new DefaultListableBeanFactory();
        factory.setAllowBeanDefinitionOverriding(false);
        var scanner = new ClassPathBeanDefinitionScanner(factory, false);
        var controllerTypes = Set.of(LMS_ADMIN, AGENT_ADMIN);
        scanner.addIncludeFilter((metadata, readerFactory) ->
                controllerTypes.contains(metadata.getClassMetadata().getClassName()));

        if (agentFirst) {
            scanner.scan("com.abandonware.ai.agent.web", "com.example.lms.api");
        } else {
            scanner.scan("com.example.lms.api", "com.abandonware.ai.agent.web");
        }

        assertEquals(LMS_ADMIN, factory.getBeanDefinition("adminController").getBeanClassName());
        assertEquals(AGENT_ADMIN, factory.getBeanDefinition("agentAdminController").getBeanClassName());
        assertEquals(0, factory.getSingletonCount(), "metadata scanning must not construct services");
    }
}
