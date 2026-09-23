package com.example.lms.service.rag.chain.impl;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.service.rag.chain.*;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.chat.ChatStreamEmitter;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.prompt.PromptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;




@Component
@RequiredArgsConstructor
public class ChainRunner {
    private static final Logger log = LoggerFactory.getLogger(ChainRunner.class);
    private static final String FAULT_MASK_STAGE = NightmareKeys.RAG_CHAIN_HANDLER;
    private static final String SILENT_CONTEXT = "component=ChainRunner;outcome=PASS;reason=handler_exception";

    private final com.example.lms.service.rag.chain.LocationInterceptHandler locationInterceptHandler;
    private final com.example.lms.service.rag.chain.AttachmentContextHandler attachmentContextHandler;
    private final com.example.lms.service.rag.chain.ImagePromptGroundingHandler imagePromptGroundingHandler;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;
    private final ObjectProvider<FaultMaskingLayerMonitor> faultMaskingLayerMonitorProvider;
    private final ObjectProvider<NightmareBreaker> nightmareBreakerProvider;

    public ChainOutcome run(String sessionId, String userId, String userMessage, ChatStreamEmitter emitter) {
        return run(sessionId, userId, userMessage, emitter, null);
    }

    public ChainOutcome run(
            String sessionId,
            String userId,
            String userMessage,
            ChatStreamEmitter emitter,
            ChatRunExecutionContext runContext) {
        return run(sessionId, userId, userMessage, emitter, runContext, null);
    }

    public ChainOutcome run(
            String sessionId,
            String userId,
            String userMessage,
            ChatStreamEmitter emitter,
            ChatRunExecutionContext runContext,
            AttachmentOwnerIdentity attachmentOwnerIdentity) {
        PromptContext ctx = com.example.lms.prompt.PromptContext.builder()
                .userQuery(userMessage)
                .build();
        DefaultChainContext dctx = new DefaultChainContext(
                sessionId,
                userId,
                userMessage,
                ctx,
                emitter,
                runContext,
                attachmentOwnerIdentity);
        DefaultChain chain = new DefaultChain(Arrays.asList(
                locationInterceptHandler,
                attachmentContextHandler,
                imagePromptGroundingHandler
        ));
        try {
            return chain.proceed(dctx);
        } catch (Exception e) {
            log.warn(
                    "[AWX2AF2][rag][chain] fail-soft outcome=PASS exceptionType={} hasSessionId={} sessionHash={} hasUserId={} hasEmitter={}",
                    exceptionType(e),
                    hasText(sessionId),
                    shortHash(sessionId),
                    hasText(userId),
                    emitter != null);
            recordFailSoftDiagnostics(e);
            emitFailSoftDebugEvent(sessionId, userId, emitter, e);
            return ChainOutcome.PASS;
        }
    }

    private void recordFailSoftDiagnostics(Exception e) {
        String reason = "handler_exception:" + exceptionType(e);
        try {
            FaultMaskingLayerMonitor monitor = faultMaskingLayerMonitorProvider == null
                    ? null
                    : faultMaskingLayerMonitorProvider.getIfAvailable();
            if (monitor != null) {
                monitor.record(FAULT_MASK_STAGE, e, SILENT_CONTEXT, reason);
            }
        } catch (Exception ignored) {
            // Diagnostics must never change the chain's fail-soft behavior.
            log.debug("[ChainRunner] fail-soft stage={}", "recordFaultMasking");
        }
        try {
            NightmareBreaker breaker = nightmareBreakerProvider == null
                    ? null
                    : nightmareBreakerProvider.getIfAvailable();
            if (breaker != null) {
                breaker.signalSilentFailure(NightmareKeys.RAG_CHAIN_HANDLER, SILENT_CONTEXT, reason);
            }
        } catch (Exception ignored) {
            // Diagnostics must never change the chain's fail-soft behavior.
            log.debug("[ChainRunner] fail-soft stage={}", "recordNightmareBreaker");
        }
    }

    private void emitFailSoftDebugEvent(String sessionId, String userId, ChatStreamEmitter emitter, Exception e) {
        try {
            DebugEventStore store = debugEventStoreProvider == null ? null : debugEventStoreProvider.getIfAvailable();
            if (store == null) {
                return;
            }
            store.emit(
                    DebugProbeType.FAULT_MASK,
                    DebugEventLevel.WARN,
                    "chainrunner.failsoft." + exceptionType(e),
                    "ChainRunner fail-soft returned PASS after handler exception",
                    "ChainRunner.run",
                    Map.of(
                            "component", "ChainRunner",
                            "outcome", ChainOutcome.PASS.name(),
                            "exceptionType", exceptionType(e),
                            "nightmareKey", NightmareKeys.RAG_CHAIN_HANDLER,
                            "faultMaskStage", FAULT_MASK_STAGE,
                            "hasSessionId", hasText(sessionId),
                            "sessionHash", shortHash(sessionId),
                            "hasUserId", hasText(userId),
                            "hasEmitter", emitter != null),
                    null);
        } catch (Exception ignored) {
            // Diagnostics must never change the chain's fail-soft behavior.
            log.debug("[ChainRunner] fail-soft stage={}", "emitFailSoftDebugEvent");
        }
    }

    private static String exceptionType(Exception e) {
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String shortHash(String value) {
        if (!hasText(value)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(12);
            for (int i = 0; i < Math.min(6, hash.length); i++) {
                out.append(String.format("%02x", hash[i]));
            }
            return out.toString();
        } catch (Exception e) {
            log.debug("[ChainRunner] fail-soft stage={}", "shortHash");
            return Integer.toHexString(value.hashCode());
        }
    }
}
