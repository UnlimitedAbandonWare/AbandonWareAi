package com.example.lms.service;

import com.example.lms.llm.ChatGptOAuthRegistration;
import com.example.lms.llm.gateway.CloudModelRouteClassifier;
import com.example.lms.llm.gateway.LlmResponseTerminalException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatGptCatalogIsolationContractTest {
    final String ownerA=AttachmentOwnerIdentity.forAnonymous("fixture-a").hash();
    @Test void disablingAutomaticMainLeavesSubscriptionCatalogueAvailable() throws Exception {
        credentials(3600,false);models("synced","fixture-gpt");
        var reg=registration(ms->false);
        ReflectionTestUtils.setField(reg,"mainRoutingEnabled",false);
        assertNull(reg.automaticMainRoute());
        assertTrue(reg.available("chatgpt-oauth:fixture-gpt"));
    }
    final String ownerB=AttachmentOwnerIdentity.forAnonymous("fixture-b").hash();
    void ownMode(ChatGptOAuthRegistration registration) {
        ReflectionTestUtils.setField(registration,"ownAccountOnly",true);
        ReflectionTestUtils.setField(registration,"registeredOwnerHash",ownerA);
        ReflectionTestUtils.setField(registration,"accountRef",fingerprint("synthetic-test-value"));
    }
    @SuppressWarnings("unchecked")
    List<ChatModelCatalogService.Choice> ownerChoices(ChatModelCatalogService catalog,String owner) {
        return ReflectionTestUtils.invokeMethod(catalog,"choices",false,owner);
    }
    @Test void ownAccountModeIsDefaultFalseAndDoesNotRequireLogin() throws Exception {
        credentials(3600,false); models("synced","fixture-gpt");
        var reg=registration(ms->false);
        assertEquals(false,ReflectionTestUtils.getField(reg,"ownAccountOnly"));
        assertTrue(reg.available("chatgpt-oauth:fixture-gpt"));
    }
    @Test void accountCatalogIsReprojectedForOwnerWithoutSharedCacheLeak() throws Exception {
        credentials(3600,false); models("synced","fixture-gpt");
        var reg=registration(ms->false); ownMode(reg); var catalog=catalog(reg);
        assertEquals(2,ownerChoices(catalog,ownerA).size());
        assertEquals(1,ownerChoices(catalog,ownerB).size());
        assertEquals(1,ownerChoices(catalog,null).size());
        assertEquals(2,ownerChoices(catalog,ownerA).size());
    }
    @Test void modelConstructionRejectsForeignOrUnboundOwner() throws Exception {
        credentials(3600,false); models("synced","fixture-gpt");
        var reg=registration(ms->false); ownMode(reg);
        for(String owner:new String[]{ownerB,null}) {
            ReflectionTestUtils.invokeMethod(com.example.lms.llm.RequestedModelSelection.class,"begin",null,owner);
            assertThrows(LlmResponseTerminalException.class,()->reg.modelFor("chatgpt-oauth:fixture-gpt",1000));
        }
    }
    @Test void capturedAssociationIsRecheckedBeforeTokenDispatch() throws Exception {
        credentials(3600,false); models("synced","fixture-gpt");
        var reg=registration(ms->{fail("no refresh for a changed association");return false;}); ownMode(reg);
        ReflectionTestUtils.invokeMethod(com.example.lms.llm.RequestedModelSelection.class,"begin",null,ownerA);
        var model=reg.modelFor("chatgpt-oauth:fixture-gpt",1000);
        var supplier=(java.util.function.Supplier<?>)ReflectionTestUtils.getField(model,"oauthAccessToken");
        assertNotNull(supplier);
        ReflectionTestUtils.setField(reg,"accountRef","account-b");
        assertThrows(LlmResponseTerminalException.class,supplier::get);
    }
    @org.junit.jupiter.api.AfterEach void clearRequestOwner() { com.example.lms.search.TraceStore.clear(); }
    static String fingerprint(String source) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,12);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    void replaceAccount(String source, boolean replaceCatalog) throws Exception {
        var doc=JSON.readTree(credentials().toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)doc).put("access_token",source);
        JSON.writeValue(credentials().toFile(),doc);
        if(replaceCatalog) {
            var catalogue=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(models().toFile());
            catalogue.put("catalog_account_fingerprint",fingerprint(source));
            JSON.writeValue(models().toFile(),catalogue);
        }
    }
    @Test void ownModeRejectsForeignCredentialsWithOldCatalogue() throws Exception {
        credentials(3600,false);models("synced","fixture-gpt");
        var reg=registration(ms->{fail("foreign credentials never refresh");return false;});ownMode(reg);
        replaceAccount("synthetic-other-value",false);
        assertTrue(reg.models(ownerA).isEmpty());
    }
    @Test void capturedAccountRejectsReplacementOfBothFiles() throws Exception {
        credentials(3600,false);models("synced","fixture-gpt");
        var reg=registration(ms->{fail("foreign credentials never refresh");return false;});ownMode(reg);
        ReflectionTestUtils.invokeMethod(com.example.lms.llm.RequestedModelSelection.class,"begin",null,ownerA);
        var model=reg.modelFor("chatgpt-oauth:fixture-gpt",1000);
        var supplier=(java.util.function.Supplier<?>)ReflectionTestUtils.getField(model,"oauthAccessToken");
        replaceAccount("synthetic-other-value",true);
        assertThrows(LlmResponseTerminalException.class,supplier::get);
    }
    @Test void localSubjectFingerprintSurvivesSyntheticTokenRotation() throws Exception {
        credentials(3600,false);models("synced","fixture-gpt");
        var doc=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(credentials().toFile());
        String payload=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"synthetic-subject\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        doc.put("id_token","fixture."+payload+".unsigned");
        JSON.writeValue(credentials().toFile(),doc);
        var catalogue=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(models().toFile());
        catalogue.put("catalog_account_fingerprint",fingerprint("synthetic-subject"));
        JSON.writeValue(models().toFile(),catalogue);
        var reg=registration(ms->false);ownMode(reg);
        ReflectionTestUtils.setField(reg,"accountRef",fingerprint("synthetic-subject"));
        replaceAccount("synthetic-rotated-value",false);
        assertEquals(List.of("fixture-gpt"),reg.models(ownerA));
    }
    @TempDir Path temp;
    static final ObjectMapper JSON = new ObjectMapper();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC);
    Path credentials() { return temp.resolve("synthetic-credentials.json"); }
    Path models() { return temp.resolve("synthetic-models.json"); }
    void credentials(long remaining, boolean refreshable) throws Exception {
        var doc = new LinkedHashMap<String,Object>();
        doc.put("schemaVersion", "awx.chatgpt-oauth.v1");
        doc.put("access_token", "synthetic-test-value");
        doc.put("token_type", "Bearer");
        doc.put("scope", "openid chatgpt.tokens.use.direct");
        doc.put("expires_at", CLOCK.instant().getEpochSecond() + remaining);
        if (refreshable) doc.put("refresh_token", "synthetic-refresh-value");
        JSON.writeValue(credentials().toFile(), doc);
    }
    void models(String status, String... models) throws Exception {
        JSON.writeValue(models().toFile(), Map.of("schemaVersion","awx.chatgpt-oauth-models.v1",
                "status",status,"count",models.length,"models",List.of(models),
                "catalog_account_fingerprint",fingerprint("synthetic-test-value")));
    }
    ChatGptOAuthRegistration registration(ChatGptOAuthRegistration.TokenRefresh refresh) {
        return new ChatGptOAuthRegistration(true, credentials(), models(), 300, CLOCK, refresh);
    }
    ChatModelCatalogService catalog(ChatGptOAuthRegistration registration) {
        var cloud = mock(CloudModelRouteClassifier.class);
        var row = mock(CloudModelRouteClassifier.CloudModelRouteRow.class);
        when(row.routeKey()).thenReturn("api-fixture");
        when(row.provider()).thenReturn("openai");
        when(row.modelId()).thenReturn("fixture-gpt");
        when(row.eligible()).thenReturn(true);
        when(cloud.classifyDefaultCatalog("chat")).thenReturn(List.of(row));
        var catalog = new ChatModelCatalogService(cloud, null, new RestTemplateBuilder(),
                "https://synthetic.invalid", true);
        ReflectionTestUtils.setField(catalog,"chatGptOAuth",registration);
        return catalog;
    }

    @Test void oauthAndApiKeyNamesNeverCollideOrPolluteTheServerCache() throws Exception {
        credentials(3600,false); models("synced","fixture-gpt","fixture-fast","fixture-gpt");
        var catalog = catalog(registration(ms -> { fail("metadata must not refresh"); return false; }));
        assertEquals(List.of("llmrouter.api-fixture","chatgpt-oauth:fixture-gpt","chatgpt-oauth:fixture-fast"),
                catalog.choices().stream().map(ChatModelCatalogService.Choice::id).toList());
        var selected = catalog.resolve("chatgpt-oauth:fixture-gpt").orElseThrow();
        assertTrue(selected.selectable());
        assertEquals("chatgpt_oauth",selected.provider());
        assertEquals("fixture-gpt",selected.modelId());
        models("synced","fixture-new");
        assertTrue(catalog.resolve("chatgpt-oauth:fixture-gpt").isEmpty());
        assertTrue(catalog.resolve("chatgpt-oauth:fixture-new").isPresent());
        assertTrue(catalog.resolve("llmrouter.api-fixture").orElseThrow().selectable());
    }

    @Test void missingUnsyncedAndMalformedFilesOnlyDisableOauth() throws Exception {
        var catalog = catalog(registration(ms -> false));
        assertEquals(1,catalog.choices().size());
        credentials(3600,false); models("not_synced","fixture-gpt");
        assertEquals(1,catalog.choices().size());
        Files.writeString(models(),"{invalid");
        assertEquals(1,catalog.choices().size());
        models("synced","fixture-gpt");
        Files.writeString(credentials(),"{invalid");
        assertEquals(1,catalog.choices().size());
    }

    @Test void otherRegistrationInstancesCannotSeeEachOthersModelFiles() throws Exception {
        credentials(3600,false); models("synced","fixture-a");
        Path otherModels=temp.resolve("other-models.json");
        JSON.writeValue(otherModels.toFile(),Map.of("schemaVersion","awx.chatgpt-oauth-models.v1",
                "status","synced","models",List.of("fixture-b")));
        var a=catalog(registration(ms->false));
        var b=catalog(new ChatGptOAuthRegistration(true,credentials(),otherModels,300,CLOCK,ms->false));
        assertTrue(a.resolve("chatgpt-oauth:fixture-b").isEmpty());
        assertTrue(b.resolve("chatgpt-oauth:fixture-a").isEmpty());
        assertTrue(a.resolve("chatgpt-oauth:fixture-a").isPresent());
        assertTrue(b.resolve("chatgpt-oauth:fixture-b").isPresent());
    }

    @Test void expiredUnrefreshableAndDisabledRegistrationsAreUnavailable() throws Exception {
        models("synced","fixture-gpt"); credentials(299,false);
        assertTrue(registration(ms->false).models().isEmpty());
        credentials(3600,false);
        assertTrue(new ChatGptOAuthRegistration(false,credentials(),models(),300,CLOCK,ms->false).models().isEmpty());
    }

    @Test void refreshOnlyRunsAtDispatchAndIsSerializedThroughHarnessContract() throws Exception {
        credentials(299,true); models("synced","fixture-gpt");
        var calls=new AtomicInteger();
        var registration=registration(ms->{calls.incrementAndGet(); credentials(3600,true); return true;});
        assertEquals(List.of("fixture-gpt"),registration.models());
        registration.modelFor("chatgpt-oauth:fixture-gpt",1000);
        assertEquals(0,calls.get());
        assertEquals("synthetic-test-value",registration.accessToken("chatgpt-oauth:fixture-gpt",1000));
        assertEquals("synthetic-test-value",registration.accessToken("chatgpt-oauth:fixture-gpt",1000));
        assertEquals(1,calls.get());
    }

    @Test void refreshFailureAndRevocationNeverSupplyAStaleTokenOrRawDiagnostic() throws Exception {
        credentials(299,true); models("synced","fixture-gpt");
        var registration=registration(ms->{throw new IllegalStateException("synthetic-private-diagnostic");});
        var failure=assertThrows(LlmResponseTerminalException.class,
                ()->registration.accessToken("chatgpt-oauth:fixture-gpt",1000));
        assertFalse(failure.toString().contains("private-diagnostic"));
        assertNull(failure.getCause());
        Files.delete(credentials());
        assertThrows(LlmResponseTerminalException.class,()->registration.modelFor("chatgpt-oauth:fixture-gpt",1000));
    }

    @Test void selectionMustMatchExactCatalogNamespaceAndNoRefreshForMissingModel() throws Exception {
        credentials(299,true); models("synced","fixture-gpt","https://bad.invalid","../invalid","");
        var calls=new AtomicInteger();
        var registration=registration(ms->{calls.incrementAndGet(); return false;});
        assertFalse(registration.available("fixture-gpt"));
        assertThrows(LlmResponseTerminalException.class,()->registration.modelFor("chatgpt-oauth:missing",1000));
        assertEquals(List.of("fixture-gpt"),registration.models());
        assertEquals(0,calls.get());
    }
}
