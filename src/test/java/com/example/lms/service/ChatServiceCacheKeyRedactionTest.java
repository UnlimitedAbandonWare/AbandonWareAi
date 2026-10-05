package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.config.CacheConfig;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceCacheKeyRedactionTest {

    @Test
    void cacheKeyUsesMessageHashInsteadOfRawMessage() {
        String raw = "private chat message should not appear in cache keys";
        ChatRequestDto req = ChatRequestDto.builder()
                .sessionId(7L)
                .model("local-model")
                .message(raw)
                .useRag(Boolean.TRUE)
                .useWebSearch(Boolean.FALSE)
                .build();

        String key = ChatService.cacheKey(req);

        assertFalse(key.contains(raw));
        assertTrue(key.contains(SafeRedactor.hash12(raw)));
    }

    @Test
    void omittedRetrievalIntentDoesNotShareAnEntryWithExplicitOff() {
        AtomicInteger invocations = new AtomicInteger();
        ChatWorkflow cached = cachedWorkflow(invocations);
        ChatRequestDto omitted = cacheSafeRequest().toBuilder()
                .useRag(null)
                .build();
        ChatRequestDto explicitOff = omitted.toBuilder()
                .useRag(Boolean.FALSE)
                .build();

        assertEquals("run-1:balanced", cached.continueChat(omitted).content());
        assertEquals("run-2:balanced", cached.continueChat(explicitOff).content());
        assertEquals(2, invocations.get());
    }

    @Test
    void meaningfullyDifferentCacheSafeRequestsUseDistinctEntries() {
        AtomicInteger invocations = new AtomicInteger();
        ChatWorkflow cached = cachedWorkflow(invocations);
        ChatRequestDto fact = cacheSafeRequest().toBuilder().mode("fact").build();
        ChatRequestDto creative = fact.toBuilder().mode("creative").build();

        assertEquals("run-1:fact", cached.continueChat(fact).content());
        assertEquals("run-2:creative", cached.continueChat(creative).content());
        assertEquals(2, invocations.get());
    }

    @Test
    void statefulAndSideEffectRequestsBypassResponseCache() {
        List<ChatRequestDto> unsafeRequests = List.of(
                cacheSafeRequest().toBuilder().sessionId(918273645546372819L).build(),
                cacheSafeRequest().toBuilder().memoryMode("full").build(),
                cacheSafeRequest().toBuilder().useWebSearch(Boolean.TRUE).build(),
                cacheSafeRequest().toBuilder().attachmentIds(List.of("attachment-a12")).build(),
                cacheSafeRequest().toBuilder()
                        .history(List.of(ChatRequestDto.Message.builder()
                                .role("user")
                                .content("prior turn")
                                .build()))
                        .build(),
                cacheSafeRequest().toBuilder().understandingEnabled(true).build(),
                cacheSafeRequest().toBuilder().contextPreparationRequested(true).build(),
                cacheSafeRequest().toBuilder().contextSourceCheck(() -> {}).build());

        for (int index = 0; index < unsafeRequests.size(); index++) {
            AtomicInteger invocations = new AtomicInteger();
            ChatWorkflow cached = cachedWorkflow(invocations);
            ChatRequestDto unsafe = unsafeRequests.get(index);

            ChatResult first = cached.continueChat(unsafe);
            ChatResult second = cached.continueChat(unsafe);

            assertNotEquals(first.content(), second.content(), "unsafe request " + index + " was cached");
            assertEquals(2, invocations.get(), "unsafe request " + index + " skipped execution");
        }
    }

    @Test
    void identicalCacheSafeRequestsRetainTheExistingHitPolicy() {
        AtomicInteger invocations = new AtomicInteger();
        ChatWorkflow cached = cachedWorkflow(invocations);
        ChatRequestDto request = cacheSafeRequest();

        ChatResult first = cached.continueChat(request);
        ChatResult second = cached.continueChat(request);

        assertEquals(first, second);
        assertEquals(1, invocations.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredResponseCacheHitsBeforeFiveMinutesAndExpiresAtTheBoundary() {
        CaffeineCacheManager manager = (CaffeineCacheManager) new CacheConfig().cacheManager();
        Caffeine<Object, Object> configuredBuilder = (Caffeine<Object, Object>)
                ReflectionTestUtils.getField(manager, "cacheBuilder");
        AtomicLong tickerNanos = new AtomicLong();
        var nativeCache = configuredBuilder.ticker(tickerNanos::get).build();
        manager.registerCustomCache("chatResponses", nativeCache);
        assertEquals(300_000L, nativeCache.policy().expireAfterWrite().orElseThrow()
                .getExpiresAfter(TimeUnit.MILLISECONDS));
        assertEquals(500L, nativeCache.policy().eviction().orElseThrow().getMaximum());
        AtomicInteger invocations = new AtomicInteger();
        ChatWorkflow cached = cachedWorkflow(invocations, manager);
        ChatRequestDto request = cacheSafeRequest();
        ChatResult first = cached.continueChat(request);

        tickerNanos.set(TimeUnit.MILLISECONDS.toNanos(299_999L));
        assertEquals(first, cached.continueChat(request));
        assertEquals(1, invocations.get());

        tickerNanos.set(TimeUnit.MILLISECONDS.toNanos(300_000L));
        ChatResult expired = cached.continueChat(request);
        assertNotEquals(first, expired);
        assertEquals(2, invocations.get());
        assertEquals(expired, cached.continueChat(request));
        assertEquals(2, invocations.get());
    }

    @Test
    void unverifiedOwnerRequestsBypassResponseCache() {
        AtomicInteger invocations = new AtomicInteger();
        ChatWorkflow cached = cachedWorkflow(invocations);
        ChatRequestDto request = unboundCacheSafeRequest();

        assertFalse(ChatService.isCacheSafe(request));
        assertNotEquals(cached.continueChat(request), cached.continueChat(request));
        assertEquals(2, invocations.get());
    }

    @Test
    void sameQuestionIsIsolatedByServerVerifiedOwner() {
        AtomicInteger invocations = new AtomicInteger();
        ChatWorkflow cached = cachedWorkflow(invocations);
        ChatRequestDto firstOwner = cacheSafeRequest("owner-a");
        ChatRequestDto secondOwner = cacheSafeRequest("owner-b");

        ChatResult first = cached.continueChat(firstOwner);
        ChatResult second = cached.continueChat(secondOwner);

        assertNotEquals(ChatService.cacheKey(firstOwner), ChatService.cacheKey(secondOwner));
        assertNotEquals(first, second);
        assertEquals(first, cached.continueChat(firstOwner));
        assertEquals(second, cached.continueChat(secondOwner));
        assertEquals(2, invocations.get());
        assertFalse(ChatService.cacheKey(firstOwner).contains(firstOwner.getVerifiedRequestOwnerHash()));
    }

    @Test
    void sameOwnerDifferentQueryAndExecutionPreferencesNeverReuseAnAnswer() {
        ChatRequestDto base = cacheSafeRequest();
        List<ChatRequestDto> variations = List.of(
                base.toBuilder().message("different question").build(),
                base.toBuilder().customInstructions("prefer concise answers").build(),
                base.toBuilder().responseTone("friendly").build(),
                base.toBuilder().responseLength("short").build(),
                base.toBuilder().responseLanguage("ko").build(),
                base.toBuilder().strictModelSelection(true).build(),
                base.toBuilder().modelSelectionMode("manual").build(),
                base.toBuilder().executionMode(com.example.lms.domain.enums.ExecutionMode.SELF_ASK).build(),
                base.toBuilder().ragAnswerPolicy("evidence_only").build());
        AtomicInteger invocations = new AtomicInteger();
        ChatWorkflow cached = cachedWorkflow(invocations);
        ChatResult first = cached.continueChat(base);

        for (ChatRequestDto request : variations) {
            assertNotEquals(ChatService.cacheKey(base), ChatService.cacheKey(request));
            assertNotEquals(first, cached.continueChat(request));
        }
        assertEquals(variations.size() + 1, invocations.get());
    }

    @Test
    void revisionDependentSettingsSnapshotsBypassResponseCache() {
        AtomicInteger invocations = new AtomicInteger();
        ChatWorkflow cached = cachedWorkflow(invocations);
        ChatRequestDto first = cacheSafeRequest();
        ChatRequestDto revised = cacheSafeRequest();
        first.bindChatSettingsSnapshot(new ChatRequestDto.ChatSettingsSnapshot(
                java.util.Map.of(), java.util.Map.of("defaultsVersion", "revision-a"), java.util.Map.of()));
        revised.bindChatSettingsSnapshot(new ChatRequestDto.ChatSettingsSnapshot(
                java.util.Map.of(), java.util.Map.of("defaultsVersion", "revision-b"), java.util.Map.of()));

        assertFalse(ChatService.isCacheSafe(first));
        assertFalse(ChatService.isCacheSafe(revised));
        assertNotEquals(cached.continueChat(first), cached.continueChat(first));
        assertNotEquals(cached.continueChat(revised), cached.continueChat(revised));
        assertEquals(4, invocations.get());
    }

    @Test
    void cacheKeyIsBoundedAndDoesNotExposeRawIdentityInputs() {
        String rawMessage = "private-query-a12";
        String rawSystemPrompt = "private-system-a12";
        String rawTrait = "private-trait-a12";
        String rawAttachment = "private-attachment-a12";
        Long rawSession = 918273645546372819L;
        ChatRequestDto request = cacheSafeRequest().toBuilder()
                .message(rawMessage)
                .systemPrompt(rawSystemPrompt)
                .traits(List.of(rawTrait))
                .attachmentIds(List.of(rawAttachment))
                .sessionId(rawSession)
                .build();

        String key = ChatService.cacheKey(request);

        assertTrue(key.length() <= 96);
        assertFalse(key.contains(rawMessage));
        assertFalse(key.contains(rawSystemPrompt));
        assertFalse(key.contains(rawTrait));
        assertFalse(key.contains(rawAttachment));
        assertFalse(key.contains(String.valueOf(rawSession)));
    }

    private static ChatRequestDto cacheSafeRequest() {
        return cacheSafeRequest("owner-a");
    }

    private static ChatRequestDto cacheSafeRequest(String owner) {
        ChatRequestDto request = unboundCacheSafeRequest();
        request.bindVerifiedRequestOwner(AttachmentOwnerIdentity.forAnonymous(owner));
        return request;
    }

    private static ChatRequestDto unboundCacheSafeRequest() {
        return ChatRequestDto.builder()
                .message("same cache-safe question")
                .model("model-a")
                .mode("balanced")
                .memoryMode("ephemeral")
                .temperature(0.2d)
                .topP(1.0d)
                .frequencyPenalty(0.0d)
                .presencePenalty(0.0d)
                .maxTokens(256)
                .useRag(Boolean.FALSE)
                .useWebSearch(Boolean.FALSE)
                .useVerification(Boolean.FALSE)
                .build();
    }

    private static ChatWorkflow cachedWorkflow(AtomicInteger invocations) {
        return cachedWorkflow(invocations, new ConcurrentMapCacheManager("chatResponses"));
    }

    private static ChatWorkflow cachedWorkflow(AtomicInteger invocations, CacheManager manager) {
        ChatWorkflow target = mock(ChatWorkflow.class);
        when(target.continueChat(any(ChatRequestDto.class))).thenAnswer(call -> {
            ChatRequestDto request = call.getArgument(0);
            return ChatResult.of(
                    "run-" + invocations.incrementAndGet() + ":" + request.getMode(),
                    "model-a",
                    false);
        });

        CacheInterceptor interceptor = new CacheInterceptor();
        interceptor.setCacheManager(manager);
        interceptor.setCacheOperationSources((method, targetClass) -> cacheOperations());
        interceptor.afterPropertiesSet();
        interceptor.afterSingletonsInstantiated();

        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (ChatWorkflow) factory.getProxy();
    }

    private static Collection<CacheOperation> cacheOperations() {
        try {
            Method method = ChatWorkflow.class.getMethod("continueChat", ChatRequestDto.class);
            Collection<CacheOperation> operations = new AnnotationCacheOperationSource()
                    .getCacheOperations(method, ChatWorkflow.class);
            if (operations == null || operations.isEmpty()) {
                throw new IllegalStateException("canonical chat cache operation missing");
            }
            return operations;
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException(error);
        }
    }
}
