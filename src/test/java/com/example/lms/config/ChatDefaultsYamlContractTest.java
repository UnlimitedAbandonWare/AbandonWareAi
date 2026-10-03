package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatDefaultsYamlContractTest {
    @Test
    void factorySearchOffStaysAStringWhenSpringLoadsYaml() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("factory", new ClassPathResource("application-llm.yaml")).get(0);
        assertEquals("OFF", source.getProperty("chat.defaults.search-mode"));
        var binder = new Binder(ConfigurationPropertySources.from(source));
        assertEquals("OFF", binder.bind("chat.defaults.search-mode", String.class).get());
        assertEquals(Boolean.FALSE, source.getProperty("chat.defaults.use-web-search"));
        assertEquals(Boolean.TRUE, source.getProperty("chat.defaults.use-rag"));
    }
}
