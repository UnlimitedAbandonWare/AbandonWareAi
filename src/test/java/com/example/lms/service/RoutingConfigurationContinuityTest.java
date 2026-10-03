package com.example.lms.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Configuration/isolated H2 proof only: no production migration or live account. */
class RoutingConfigurationContinuityTest {
    @TempDir Path temp;
    @Test void T24BenefitAndOwnAccountFlagsLeaveEmbeddingPropertiesUnchanged() throws Exception {
        var env=new MockEnvironment();
        var loader=new YamlPropertySourceLoader();
        for(var source:loader.load("llm",new ClassPathResource("application-llm.yaml")))env.getPropertySources().addLast(source);
        for(var source:loader.load("local",new ClassPathResource("application-local.yml")))env.getPropertySources().addFirst(source);
        var keys=List.of("embedding.provider","embedding.model","embedding.base-url","embedding.base-url-fallback",
                "embedding.dimension","embedding.cross-gpu-fallback.enabled","embedding.port-fallback.enabled");
        var before=new LinkedHashMap<String,String>(); for(String key:keys)before.put(key,env.getProperty(key));
        assertNotNull(before.get("embedding.model")); assertNotNull(before.get("embedding.base-url"));
        env.withProperty("chatgpt.oauth.enabled","false").withProperty("chatgpt.oauth.own-account-only","true")
                .withProperty("chatgpt.oauth.benefit.operational-cutoff","2026-10-01T00:00:00Z");
        for(String key:keys)assertEquals(before.get(key),env.getProperty(key),key);
    }
    @Test void T25DisablingRouteKeepsExistingH2SettingsAndConversationRows() throws Exception {
        String url="jdbc:h2:file:"+temp.resolve("rollback-state").toAbsolutePath();
        try(var db=java.sql.DriverManager.getConnection(url)) {
            db.createStatement().execute("CREATE TABLE configuration_settings(setting_key VARCHAR(100) PRIMARY KEY,setting_value CLOB)");
            db.createStatement().execute("CREATE TABLE conversation_fixture(id INT PRIMARY KEY,body VARCHAR(100))");
            db.createStatement().execute("INSERT INTO conversation_fixture VALUES(1,'synthetic retained')");
            var f=new ChatGptCatalogIsolationContractTest();f.temp=temp;f.credentials(3600,false);f.models("synced","fixture-model");
            var active=f.registration(ms->false);
            org.springframework.test.util.ReflectionTestUtils.setField(active,"accountRef","account-a");
            org.springframework.test.util.ReflectionTestUtils.setField(active,"benefitSettings",RoutingBenefitLifecycleTest.jdbcRepository(db));
            active.observeTerminal(new com.example.lms.llm.gateway.LlmResponseTerminalException("fixture",
                    com.example.lms.llm.gateway.LlmFailureClass.PROVIDER_ERROR,null,null,"failed",null,
                    "subscription_sharing_usage_limit_exceeded"));
        }
        try(var db=java.sql.DriverManager.getConnection(url)) {
            var disabled=new com.example.lms.llm.ChatGptOAuthRegistration(true,temp.resolve("synthetic-credentials.json"),
                    temp.resolve("synthetic-models.json"),300,ChatGptCatalogIsolationContractTest.CLOCK,ms->false);
            org.springframework.test.util.ReflectionTestUtils.setField(disabled,"mainRoutingEnabled",false);
            assertNull(disabled.automaticMainRoute());
            assertEquals(List.of("fixture-model"),disabled.models());
            try(var rows=db.createStatement().executeQuery("SELECT COUNT(*) FROM conversation_fixture")) {assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
            try(var rows=db.createStatement().executeQuery("SELECT setting_value FROM configuration_settings")) {assertTrue(rows.next());assertEquals("exhausted",rows.getString(1));}
        }
    }
}
