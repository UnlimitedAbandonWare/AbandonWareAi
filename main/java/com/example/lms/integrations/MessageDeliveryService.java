package com.example.lms.integrations;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class MessageDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(MessageDeliveryService.class);
    private static final String PROVIDER = "outbox/noop";
    private static final String DISABLED_REASON = "message delivery provider is not configured";
    private static final String SKIPPED_REASON = "provider_disabled";

    public MessageDeliveryResult sendUrl(String targetId, String text, String url) {
        return disabledResult(targetId, text, url);
    }

    public MessageDeliveryResult pushUrl(String targetId, String text, String url) {
        return sendUrl(targetId, text, url);
    }

    public MessageDeliveryResult sendMemo(String accessToken, String text) {
        return disabledResult(accessToken, text, null);
    }

    public MessageDeliveryResult pushMemo(String accessToken, String text) {
        return sendMemo(accessToken, text);
    }

    public MessageDeliveryResult sendBizUrl(String targetId, String text, String url) {
        return disabledResult(targetId, text, url);
    }

    public MessageDeliveryResult pushBizUrl(String targetId, String text, String url) {
        return sendBizUrl(targetId, text, url);
    }

    private MessageDeliveryResult disabledResult(String targetId, String text, String url) {
        String targetHash = SafeRedactor.hash12(targetId == null ? "" : targetId);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("provider", PROVIDER);
        trace.put("accepted", false);
        trace.put("targetHash", targetHash);
        trace.put("hasText", text != null && !text.isBlank());
        trace.put("hasUrl", url != null && !url.isBlank());
        trace.put("disabledReason", DISABLED_REASON);
        TraceStore.put("message.delivery.provider", PROVIDER);
        TraceStore.put("message.delivery.accepted", false);
        TraceStore.put("message.delivery.targetHash", targetHash);
        TraceStore.put("message.delivery.hasText", trace.get("hasText"));
        TraceStore.put("message.delivery.hasUrl", trace.get("hasUrl"));
        TraceStore.put("message.delivery.providerDisabled", true);
        TraceStore.put("message.delivery.skipped.reason", SKIPPED_REASON);
        TraceStore.put("message.delivery.disabledReason", DISABLED_REASON);
        log.info("[message.delivery] provider={} accepted=false targetHash={} disabledReason={}",
                PROVIDER, targetHash, DISABLED_REASON);
        return new MessageDeliveryResult(false, PROVIDER, targetHash, DISABLED_REASON, trace);
    }
}
