package com.example.lms.api;

import com.example.lms.service.ChannelOAuthService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/channels/oauth")
@RequiredArgsConstructor
public class ChannelOAuthController {
    private static final Logger log = LoggerFactory.getLogger(ChannelOAuthController.class);
    private static final String DISABLED_REASON = "channel oauth provider is not configured";
    private static final String DISABLED_REASON_CODE = "channel_oauth_provider_not_configured";
    private static final String CALLBACK_ERROR_REASON = "oauth_callback_error";
    private static final String MISSING_CODE_REASON = "missing_authorization_code";

    private final ChannelOAuthService oauthService;

    @GetMapping("/authorize")
    public String authorize(RedirectAttributes redirect) {
        traceProviderDisabled("authorize");
        log.info("[channel.oauth] provider disabled: external {}", DISABLED_REASON);
        redirect.addFlashAttribute("error", DISABLED_REASON);
        return "redirect:/channels/recipients";
    }

    @GetMapping("/callback")
    public String callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDesc,
            RedirectAttributes redirect) {

        if (error != null) {
            traceCallbackRejected(error, errorDesc);
            log.warn("[channel.oauth] callback rejected errorCode={} errorHash={} errorLength={} descriptionPresent={} descriptionHash={} descriptionLength={}",
                    SafeRedactor.traceLabelOrFallback(error, "oauth_error"),
                    SafeRedactor.hashValue(error),
                    error.length(),
                    errorDesc != null,
                    SafeRedactor.hashValue(errorDesc),
                    errorDesc == null ? 0 : errorDesc.length());
            redirect.addFlashAttribute("error", publicOAuthError(error, errorDesc));
            return "redirect:/channels/recipients";
        }

        if (code == null || code.isBlank()) {
            traceMissingCode();
            log.warn("[channel.oauth] callback missing code");
            redirect.addFlashAttribute("error", "missing authorization code");
            return "redirect:/channels/recipients";
        }

        String accessToken = oauthService.exchangeCodeForToken(code);
        if (accessToken == null || accessToken.isBlank()) {
            traceProviderDisabled("callback_exchange_token");
            log.info("[channel.oauth] callback skipped memo disabledReason={}", DISABLED_REASON);
            redirect.addFlashAttribute("error", DISABLED_REASON);
            return "redirect:/channels/recipients";
        }
        oauthService.sendMemoDefault(accessToken, "channel oauth callback received", null);
        redirect.addFlashAttribute("result", DISABLED_REASON);
        return "redirect:/channels/recipients";
    }

    private static void traceProviderDisabled(String operation) {
        TraceStore.put("channel.oauth.controller.providerDisabled", true);
        TraceStore.put("channel.oauth.controller.disabledReason", DISABLED_REASON_CODE);
        TraceStore.put("channel.oauth.controller.skipped.reason", DISABLED_REASON_CODE);
        TraceStore.put("channel.oauth.controller.operation", operation);
    }

    private static void traceCallbackRejected(String error, String errorDesc) {
        String detail = errorDesc == null || errorDesc.isBlank() ? error : errorDesc;
        TraceStore.put("channel.oauth.controller.callbackRejected", true);
        TraceStore.put("channel.oauth.controller.skipped.reason", CALLBACK_ERROR_REASON);
        TraceStore.put("channel.oauth.controller.errorCode",
                SafeRedactor.traceLabelOrFallback(error, "oauth_error"));
        TraceStore.put("channel.oauth.controller.errorHash", SafeRedactor.hashValue(detail));
        TraceStore.put("channel.oauth.controller.errorLength", detail == null ? 0 : detail.length());
        TraceStore.put("channel.oauth.controller.errorDescriptionPresent", errorDesc != null);
    }

    private static void traceMissingCode() {
        TraceStore.put("channel.oauth.controller.missingCode", true);
        TraceStore.put("channel.oauth.controller.skipped.reason", MISSING_CODE_REASON);
        TraceStore.put("channel.oauth.controller.operation", "callback_missing_code");
    }

    static String publicOAuthError(String error, String errorDesc) {
        String detail = errorDesc == null || errorDesc.isBlank() ? error : errorDesc;
        return "channel oauth callback failed: errorCode="
                + SafeRedactor.traceLabelOrFallback(error, "oauth_error")
                + " errorHash=" + SafeRedactor.hashValue(detail)
                + " errorLength=" + (detail == null ? 0 : detail.length());
    }
}
