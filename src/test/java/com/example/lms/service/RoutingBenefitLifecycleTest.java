package com.example.lms.service;

import com.example.lms.llm.ChatGptOAuthRegistration;
import com.example.lms.domain.ConfigurationSetting;
import com.example.lms.repository.ConfigurationSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic account metadata + the existing configuration repository; never reads a real account. */
class RoutingBenefitLifecycleTest {
    @TempDir Path temp;
    final Map<String, ConfigurationSetting> durable = new HashMap<>();

    ChatGptOAuthRegistration registration(String account) throws Exception {
        var f = new ChatGptCatalogIsolationContractTest(); f.temp = temp;
        f.credentials(3600, false); f.models("synced", "fixture-model");
        var r = f.registration(ms -> { fail("no refresh"); return false; });
        var repo = mock(ConfigurationSettingRepository.class);
        when(repo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(durable.get(i.getArgument(0))));
        when(repo.save(any(ConfigurationSetting.class))).thenAnswer(i -> {
            ConfigurationSetting s = i.getArgument(0); durable.put(s.getSettingKey(), s); return s;
        });
        // Exercise Spring's actual @Value binding; the empty default is never reflectively overridden.
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var properties = new HashMap<String, Object>(Map.of(
                    "chatgpt.oauth.benefit.reported-grant-credits", "62500",
                    "chatgpt.oauth.benefit.reported-expires-on", "2026-12-31"));
            if (!account.isBlank()) properties.put("chatgpt.oauth.account-ref", account);
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "synthetic-benefit", properties));
            context.registerBean(ConfigurationSettingRepository.class, () -> repo);
            context.registerBean(ChatGptOAuthRegistration.class, () -> r);
            context.refresh();
            return context.getBean(ChatGptOAuthRegistration.class);
        }
    }
    Object snapshot(ChatGptOAuthRegistration r) throws Exception {
        return r.getClass().getMethod("benefitSnapshot").invoke(r);
    }
    Object field(Object s, String name) throws Exception { return s.getClass().getMethod(name).invoke(s); }
    void exhaust(ChatGptOAuthRegistration r) throws Exception {
        r.getClass().getMethod("observeTerminal", com.example.lms.llm.gateway.LlmResponseTerminalException.class)
                .invoke(r, new com.example.lms.llm.gateway.LlmResponseTerminalException("fixture_limit",
                        com.example.lms.llm.gateway.LlmFailureClass.PROVIDER_ERROR, null, null, "failed", null,
                        "subscription_sharing_usage_limit_exceeded"));
    }
    @Test void T05ReportedGrantNeverInventsRemainingOrDebit() throws Exception {
        var s = snapshot(registration("account-a"));
        assertEquals(62500L, field(s, "reportedGrantCredits")); assertEquals("credits", field(s, "unit"));
        for (String f : List.of("observedRemaining", "observedTokens", "estimatedCredits", "actualDebitedCredits"))
            assertNull(field(s, f), f);
    }
    @Test void usageLimitNeedsRecheckWithoutInventingExhaustionOrBalance() throws Exception {
        var r = registration("account-a"); exhaust(r);
        var s = snapshot(r);
        assertEquals(false, field(s, "exhausted"));
        assertEquals(true, field(s, "usageBlocked"));
        assertEquals("usage_limit_recheck_required", field(s, "blockedReason"));
        assertEquals("usage_limit_recheck_required", durable.get(benefitKey("account-a")).getSettingValue());
        for (String f : List.of("observedRemaining", "observedTokens", "estimatedCredits", "actualDebitedCredits"))
            assertNull(field(s, f));
        assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                () -> r.modelFor("chatgpt-oauth:fixture-model", 1000));
    }
    @Test void usageUnavailable503DoesNotPersistOrBlockAnUnknownBalance() throws Exception {
        var r = registration("account-a");
        r.observeTerminal(new com.example.lms.llm.gateway.LlmResponseTerminalException("chatgpt_oauth_http_503",
                com.example.lms.llm.gateway.LlmFailureClass.HEALTH_DOWN, null, null, "failed", null,
                "subscription_sharing_usage_unavailable"));
        assertTrue(durable.isEmpty());
        var s = snapshot(r);
        assertEquals(false, field(s, "usageBlocked"));
        assertNull(field(s, "observedRemaining"));
    }
    @Test void legacyExhaustedRowRemainsBlockedWithUnknownBalance() throws Exception {
        var r = registration("account-a");
        durable.put(benefitKey("account-a"), new ConfigurationSetting(benefitKey("account-a"), "exhausted"));
        var s = snapshot(r);
        assertEquals(true, field(s, "exhausted"));
        assertEquals(true, field(s, "usageBlocked"));
        assertEquals("usage_limit_recheck_required", field(s, "blockedReason"));
        assertEquals(false, field(s, "active"));
        assertNull(field(s, "observedRemaining"));
    }
    @Test void T06UnknownExpiryZoneStaysUnknown() throws Exception {
        var s = snapshot(registration("account-a"));
        assertEquals("2026-12-31", field(s, "reportedExpiresOn"));
        assertNull(field(s, "expiresAt")); assertNull(field(s, "operationalCutoff"));
    }
    @Test void T07ConfiguredCutoffDisablesBenefitOnly() throws Exception {
        var r = registration("account-a");
        ReflectionTestUtils.setField(r, "operationalCutoff", "2026-09-29T00:00:00Z");
        assertEquals(false, field(snapshot(r), "active"));
        assertTrue(r.available("chatgpt-oauth:fixture-model"));
    }
    @Test void T08MissingAccountDoesNotBecomeConfirmedBenefit() throws Exception {
        var r = registration(""); catalogFingerprint(null);
        assertEquals(false, field(snapshot(r), "active"));
    }
    @Test void T09UnknownStoreIsEvidenceNeeded() throws Exception {
        var r = registration("account-a"); ReflectionTestUtils.setField(r, "benefitSettings", null);
        assertEquals(false, field(snapshot(r), "storageObserved"));
        assertEquals(false, field(snapshot(r), "active"));
    }
    @Test void T10ExhaustionSurvivesNewRegistration() throws Exception {
        String url="jdbc:h2:file:"+temp.resolve("benefit-state").toAbsolutePath();
        try(var db=java.sql.DriverManager.getConnection(url)) {
            db.createStatement().execute("CREATE TABLE configuration_settings(setting_key VARCHAR(100) PRIMARY KEY, setting_value CLOB)");
            var first=registration("account-a"); ReflectionTestUtils.setField(first,"benefitSettings",jdbcRepository(db)); exhaust(first);
        }
        try(var db=java.sql.DriverManager.getConnection(url)) {
            var restarted=registration("account-a"); ReflectionTestUtils.setField(restarted,"benefitSettings",jdbcRepository(db));
            assertEquals(true,field(snapshot(restarted),"usageBlocked")); assertEquals(false,field(snapshot(restarted),"active"));
            assertTrue(restarted.available("chatgpt-oauth:fixture-model"));
        }
    }
    @Test void T11AccountSwitchNeverCarriesBenefitState() throws Exception {
        var first = registration(""); catalogFingerprint(ChatGptCatalogIsolationContractTest.fingerprint("account-a"));
        exhaust(first);
        var second = registration(""); catalogFingerprint(ChatGptCatalogIsolationContractTest.fingerprint("account-b"));
        assertEquals(false, field(snapshot(second), "exhausted"));
        assertEquals(true, field(snapshot(second), "active"));
        assertEquals(1, durable.size());
    }
    void catalogFingerprint(String fingerprint) throws Exception {
        var file = temp.resolve("synthetic-models.json").toFile();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var doc = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(file);
        if (fingerprint == null) doc.remove("catalog_account_fingerprint");
        else doc.put("catalog_account_fingerprint", fingerprint);
        json.writeValue(file, doc);
    }
    String benefitKey(String account) {
        return "OAUTH_BENEFIT_" + AttachmentOwnerIdentity.forAnonymous("chatgpt-reported-grant:" + account).hash();
    }
    @Test void blankAccountRefUsesCatalogFingerprintAndPersistsExhaustion() throws Exception {
        var r = registration("");
        var model = r.modelFor("chatgpt-oauth:fixture-model", 1000);
        @SuppressWarnings("unchecked")
        var observer = (java.util.function.Consumer<com.example.lms.llm.gateway.LlmResponseTerminalException>)
                ReflectionTestUtils.getField(model, "oauthTerminalObserver");
        assertNotNull(observer, "the product adapter must retain the admitted account observer");
        observer.accept(new com.example.lms.llm.gateway.LlmResponseTerminalException("fixture_limit",
                com.example.lms.llm.gateway.LlmFailureClass.PROVIDER_ERROR, null, null, "failed", null,
                "subscription_sharing_usage_limit_exceeded"));
        assertEquals(1, durable.size(), "blank account-ref must persist the provider limit");
        assertTrue(durable.containsKey(benefitKey(ChatGptCatalogIsolationContractTest.fingerprint("synthetic-test-value"))));
        assertEquals(true, field(snapshot(r), "usageBlocked"));
        assertNull(r.automaticMainRoute(), "derived exhausted account cannot be automatically promoted");
        String replacement = ChatGptCatalogIsolationContractTest.fingerprint("account-b");
        catalogFingerprint(replacement);
        observer.accept(new com.example.lms.llm.gateway.LlmResponseTerminalException("fixture_delayed_limit",
                com.example.lms.llm.gateway.LlmFailureClass.PROVIDER_ERROR, null, null, "failed", null,
                "subscription_sharing_usage_limit_exceeded"));
        assertEquals(1, durable.size(), "late account A terminal must keep its captured key");
        assertFalse(durable.containsKey(benefitKey(replacement)));
        assertEquals(false, field(snapshot(r), "exhausted"));
        assertEquals("chatgpt-oauth:fixture-model", r.automaticMainRoute());
    }
    @Test void exhaustionSurvivesRestartWithDerivedKey() throws Exception {
        String url = "jdbc:h2:file:" + temp.resolve("derived-benefit-state").toAbsolutePath();
        try (var db = java.sql.DriverManager.getConnection(url)) {
            db.createStatement().execute("CREATE TABLE configuration_settings(setting_key VARCHAR(100) PRIMARY KEY, setting_value CLOB)");
            var first = registration(""); ReflectionTestUtils.setField(first, "benefitSettings", jdbcRepository(db));
            exhaust(first);
            try (var rows = db.createStatement().executeQuery("SELECT COUNT(*) FROM configuration_settings")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1), "derived key must reach the existing settings table");
            }
        }
        try (var db = java.sql.DriverManager.getConnection(url)) {
            var restarted = registration(""); ReflectionTestUtils.setField(restarted, "benefitSettings", jdbcRepository(db));
            assertEquals(true, field(snapshot(restarted), "usageBlocked"));
            assertNull(restarted.automaticMainRoute());
            assertTrue(restarted.available("chatgpt-oauth:fixture-model"));
        }
    }
    @Test void noAccountKeyIsExplicitUnboundNotSilent() throws Exception {
        var r = registration(""); catalogFingerprint(null);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ChatGptOAuthRegistration.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var s = snapshot(r);
            assertEquals("unbound", field(s, "bindingState"));
            assertEquals(false, field(s, "storageObserved"));
            exhaust(r); snapshot(r);
            assertEquals("chatgpt-oauth:fixture-model", r.automaticMainRoute());
            assertTrue(r.available("chatgpt-oauth:fixture-model"));
            assertTrue(durable.isEmpty());
            var warnings = appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN).toList();
            assertEquals(1, warnings.size(), "unbound observation emits one safe warning per registration");
            assertEquals("ChatGPT OAuth benefit unbound: account key unavailable", warnings.get(0).getFormattedMessage());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @Test void explicitAccountRefWinsOverCatalog() throws Exception {
        var r = registration("explicit-account"); exhaust(r);
        assertEquals(Set.of(benefitKey("explicit-account")), durable.keySet());
        assertEquals(true, field(snapshot(r), "usageBlocked"));
    }
    static ConfigurationSettingRepository jdbcRepository(java.sql.Connection db) throws Exception {
        var repo=mock(ConfigurationSettingRepository.class);
        when(repo.findById(anyString())).thenAnswer(i->{
            try(var q=db.prepareStatement("SELECT setting_value FROM configuration_settings WHERE setting_key=?")) {
                String key=i.getArgument(0);q.setString(1,key);try(var rows=q.executeQuery()) {
                    return rows.next()?Optional.of(new ConfigurationSetting(key,rows.getString(1))):Optional.empty();
                }
            }
        });
        when(repo.save(any(ConfigurationSetting.class))).thenAnswer(i->{
            ConfigurationSetting s=i.getArgument(0);
            try(var q=db.prepareStatement("MERGE INTO configuration_settings(setting_key,setting_value) KEY(setting_key) VALUES(?,?)")) {
                q.setString(1,s.getSettingKey());q.setString(2,s.getSettingValue());q.executeUpdate();return s;
            }
        });return repo;
    }
}
