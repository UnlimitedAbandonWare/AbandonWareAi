package com.example.lms.llm;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChatModel이 자신이 대표하는 백엔드 모델/경로 식별자를 노출하는 최소 계약.
 *
 * <p>익명/프록시 래퍼는 {@code getClass().getSimpleName()}이 {@code ""}로 붕괴해
 * 브레이커 키가 {@code chat:draft:unknown} 하나로 합쳐지는 결함이 있었다.
 * 래퍼가 이 인터페이스를 구현하면 라우터가 실제 모델 ID를 회복할 수 있다.</p>
 */
public interface NamedChatModel extends ChatModel {

    /**
     * 이 인스턴스가 실제로 대표하는 모델/경로 식별자(예: {@code "qwen3:8b"}).
     * 알 수 없으면 {@code null}을 반환한다 — 호출자가 다음 해석 단계로 진행한다.
     */
    String resolvedModelName();

    /**
     * 등록된 팩토리 ID를 우선 확인하고, 명명 래퍼이면 선언된 모델명을 반환한다.
     * 위임 체인 해석은 각 구현의 {@link #resolvedModelName()}이 수행한다.
     */
    static String resolve(ChatModel model) {
        if (model == null) {
            return null;
        }
        String registered = DynamicChatModelFactory.configuredModelId(model);
        if (registered != null && !registered.isBlank()) {
            return registered.trim();
        }
        if (model instanceof NamedChatModel named) {
            String declared = named.resolvedModelName();
            if (declared != null && !declared.isBlank()) {
                return declared.trim();
            }
        }
        return null;
    }

    /**
     * 브레이커 키용 모델 태그를 해석한다.
     *
     * <p>해석 결과가 붕괴 상태(공백, "unknown", 또는 단순 클래스명 — 클래스명은
     * 서로 다른 모델이 같은 브레이커를 공유하게 만드는 붕괴 신호)일 때만
     * 요청된 모델 ID로 복구한다. 요청값이 센티널("auto" 등)이면 복구하지 않는다.</p>
     */
    static String breakerTag(ChatModel model, String resolvedName, String requestedModel) {
        String resolved = resolvedName == null ? "" : resolvedName.trim();
        String simpleName = model == null ? "" : model.getClass().getSimpleName();
        boolean collapsed = resolved.isEmpty()
                || "unknown".equalsIgnoreCase(resolved)
                || resolved.startsWith("llmrouter.")
                || (!simpleName.isEmpty() && resolved.equals(simpleName));
        if (!collapsed) {
            return resolved;
        }
        // Use construction evidence from this exact client, never the last request's trace.
        if (resolved.startsWith("llmrouter.")) {
            String configured = configuredBreakerTag(model);
            if (configured != null) return configured;
            String named = resolve(model);
            if (isConcreteModelTag(named) && !named.equals(simpleName)) {
                return named;
            }
        }
        try {
            com.example.lms.search.TraceStore.put("chat.breaker.keyCollapsed", true);
            com.example.lms.search.TraceStore.put("chat.breaker.keyCollapsed.resolvedHash",
                    com.example.lms.trace.SafeRedactor.hashValue(resolved));
        } catch (RuntimeException ignore) {
            // 진단 기록 실패는 키 해석을 방해하지 않는다.
        }
        String requested = requestedModel == null ? "" : requestedModel.trim();
        if (isConcreteModelTag(requested)) {
            CollapseLog.warnOnce(requested);
            return requested;
        }
        CollapseLog.warnOnce(simpleName.isEmpty() ? resolved : simpleName);
        return resolved.isEmpty() ? "unknown" : resolved;
    }

    /** Preserve route-tag fail-soft behavior when this client has no concrete model identity. */
    private static boolean isConcreteModelTag(String tag) {
        return isUsableModelTag(tag) && !tag.trim().startsWith("llmrouter.");
    }

    private static String configuredBreakerTag(ChatModel model) {
        var identity = DynamicChatModelFactory.configuredModelIdentity(model);
        if (identity == null || !isConcreteModelTag(identity.modelId())) return null;
        String actual = identity.modelId().trim();
        return isConcreteModelTag(identity.provider()) ? identity.provider().trim() + ":" + actual : actual;
    }

    /** Model identity plus call phase; regeneration must not consume the draft's permit. */
    public static String breakerKey(ChatModel model, String resolvedName, String requestedModel, String phase) {
        String configured = configuredBreakerTag(model);
        String draftKey = com.example.lms.infra.resilience.NightmareKeys.chatDraftKey(configured == null
                ? breakerTag(model, resolvedName, requestedModel) : configured);
        return "final".equalsIgnoreCase(phase)
                ? "chat:final" + draftKey.substring("chat:draft".length()) : draftKey;
    }

    /** 브레이커 태그로 쓸 수 있는 구체 모델 ID인지 판정한다. 센티널/공백은 거부. */
    static boolean isUsableModelTag(String tag) {
        if (tag == null) {
            return false;
        }
        String t = tag.trim();
        if (t.isEmpty() || t.length() > 200) {
            return false;
        }
        switch (t.toLowerCase(java.util.Locale.ROOT)) {
            case "auto", "default", "unknown", "any", "none", "null", "*" -> {
                return false;
            }
            default -> {
            }
        }
        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 붕괴 태그별 WARN 1회 — 요청 폭풍에서 로그 범람을 막는다. */
    final class CollapseLog {
        private static final Logger LOG = LoggerFactory.getLogger(NamedChatModel.class);
        private static final java.util.Set<String> WARNED =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        private CollapseLog() {
        }

        static void warnOnce(String tag) {
            String safe = tag == null || tag.isBlank() ? "<blank>" : tag.trim();
            if (WARNED.add(safe) && WARNED.size() <= 64) {
                LOG.warn("[breaker] model identity collapsed to non-model tag={} "
                        + "(chat:draft breaker sharing risk)", safe);
            }
        }
    }
}
