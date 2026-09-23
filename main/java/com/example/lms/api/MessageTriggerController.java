// src/main/java/com/example/lms/api/MessageTriggerController.java
package com.example.lms.api;

import com.example.lms.dto.MessageFormDto;
import com.example.lms.integrations.MessageDeliveryResult;
import com.example.lms.integrations.MessageDeliveryService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;



@Controller
@RequiredArgsConstructor
@RequestMapping("/messages")
public class MessageTriggerController {

    private final MessageDeliveryService messageDelivery;

    /**
     * 1) GET  /messages/trigger : ???섏씠吏 ?뚮뜑留?     */
    @GetMapping("/trigger")
    public String showForm(Model model) {
        model.addAttribute("dto", new MessageFormDto());
        return "messages/form";  // src/main/resources/templates/messages/form.html
    }

    /**
     * 2) POST /messages/trigger (FORM) : application/x-www-form-urlencoded
     */
    @PostMapping(
            path = "/trigger",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public String triggerForm(
            @ModelAttribute("dto") MessageFormDto dto,
            RedirectAttributes redirect
    ) {
        MessageDeliveryResult result = messageDelivery.sendUrl(dto.getTargetId(), dto.getMessage(), dto.getUrl());
        MessageDeliveryResult safe = safeResult(result);
        traceDelivery("form", safe);
        redirect.addFlashAttribute("result", safe.disabledReason());
        return "redirect:/messages/trigger";
    }

    /**
     * 3) POST /messages/trigger (JSON) : application/json
     */
    @PostMapping(
            path = "/trigger",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @ResponseBody
    public ResponseEntity<MessageDeliveryResult> triggerJson(@RequestBody MessageFormDto dto) {
        if (dto == null) {
            MessageDeliveryResult safe = safeResult(MessageDeliveryResult.disabled("message-trigger", "", "invalid_payload"));
            traceDelivery("json", safe);
            return ResponseEntity.badRequest()
                    .body(safe);
        }
        MessageDeliveryResult safe = safeResult(messageDelivery.sendUrl(dto.getTargetId(), dto.getMessage(), dto.getUrl()));
        traceDelivery("json", safe);
        return ResponseEntity.ok(safe);
    }

    private static MessageDeliveryResult safeResult(MessageDeliveryResult result) {
        if (result == null) {
            result = MessageDeliveryResult.disabled("message-trigger", "", "delivery_unavailable");
        }
        String disabledReason = safeReason(result.disabledReason());
        return new MessageDeliveryResult(
                result.accepted(),
                safeReason(result.provider()),
                safeHashToken(result.targetHash()),
                disabledReason,
                safeDiagnostics(result.diagnostics(), disabledReason)
        );
    }

    private static Map<String, Object> safeDiagnostics(Map<String, Object> diagnostics, String disabledReason) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : diagnostics.entrySet()) {
            String key = SafeRedactor.traceLabelOrFallback(entry.getKey(), "field");
            if ("disabledReason".equals(key)) {
                copy.put(key, disabledReason);
            } else if ("targetHash".equals(key)) {
                copy.put(key, safeHashToken(String.valueOf(entry.getValue())));
            } else {
                copy.put(key, SafeRedactor.diagnosticValue(key, entry.getValue()));
            }
        }
        return copy;
    }

    private static String safeReason(String value) {
        return SafeRedactor.traceLabelOrFallback(value, "");
    }

    private static String safeHashToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.matches("(?i)[a-f0-9]{8,64}")) {
            return trimmed.toLowerCase(java.util.Locale.ROOT);
        }
        if (trimmed.startsWith("hash:")) {
            return SafeRedactor.traceLabelOrFallback(trimmed, "");
        }
        return SafeRedactor.hashValue(trimmed);
    }

    private static void traceDelivery(String surface, MessageDeliveryResult result) {
        TraceStore.put("message.trigger.delivery.accepted", result != null && result.accepted());
        TraceStore.put("message.trigger.delivery.surface", SafeRedactor.traceLabelOrFallback(surface, "unknown"));
        TraceStore.put("message.trigger.delivery.provider", safeReason(result == null ? null : result.provider()));
        TraceStore.put("message.trigger.delivery.targetHash", safeHashToken(result == null ? null : result.targetHash()));
        TraceStore.put("message.trigger.delivery.disabledReason",
                safeReason(result == null ? null : result.disabledReason()));
    }
}
