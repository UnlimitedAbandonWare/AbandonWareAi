// src/main/java/com/example/lms/api/MessageAdminController.java
package com.example.lms.api;

import com.example.lms.dto.MessageFormDto;
import com.example.lms.integrations.MessageDeliveryResult;
import com.example.lms.integrations.MessageDeliveryService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;



/**
 * 愿由ъ옄??移댁뭅??硫붿떆吏 諛쒖넚 而⑦듃濡ㅻ윭
 *
 * GET  /admin/messages      ??諛쒖넚 ???쒖떆
 * POST /admin/messages/send ?????곗씠?곕줈 硫붿떆吏 諛쒖넚 ??由щ떎?대젆?? */
@Controller
@RequestMapping("/admin/messages")
@RequiredArgsConstructor
public class MessageAdminController {

    private final MessageDeliveryService messageDelivery;

    /**
     * 移댁뭅??諛쒖넚 ?쇱쓣 蹂댁뿬以띾땲??
     */
    @GetMapping
    public String showForm(Model model) {
        model.addAttribute("dto", new MessageFormDto());
        return "messages/admin-form";  // src/main/resources/templates/messages/admin-form.html
    }

    /**
     * 移댁뭅??硫붿떆吏瑜?諛쒖넚?섍퀬 寃곌낵瑜?RedirectAttributes???댁븘 由щ떎?대젆?명빀?덈떎.
     */
    @PostMapping("/send")
    public String send(
            @ModelAttribute("dto") MessageFormDto dto,
            RedirectAttributes redirect
    ) {
        MessageDeliveryResult result = messageDelivery.sendUrl(
                dto.getTargetId(),
                dto.getMessage(),
                dto.getUrl()
        );
        traceDelivery(result);
        redirect.addFlashAttribute(
                "result",
                safeReason(result == null ? null : result.disabledReason())
        );
        return "redirect:/admin/messages";
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

    private static void traceDelivery(MessageDeliveryResult result) {
        TraceStore.put("message.admin.delivery.accepted", result != null && result.accepted());
        TraceStore.put("message.admin.delivery.provider", safeReason(result == null ? null : result.provider()));
        TraceStore.put("message.admin.delivery.targetHash", safeHashToken(result == null ? null : result.targetHash()));
        TraceStore.put("message.admin.delivery.disabledReason",
                safeReason(result == null ? null : result.disabledReason()));
    }
}
