package com.example.lms.guard.rulebreak;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Evaluates "rule break" headers or probe parameters to temporarily relax
 * guardrails (e.g., domain allowlist bypass, topK boost, hedge disable) for
 * debugging and QA workflows.
 *
 * <p>Activation requires an admin token that must match the incoming token. If
 * a valid token is not provided, this evaluator returns an inactive context.</p>
 */
@Component
public class RuleBreakEvaluator {

  @Value("${nova.rulebreak.admin-token:${tools.rulebreak.admin-token:}}")
  private String adminToken;

  @Value("${nova.rulebreak.ttl-seconds:${tools.rulebreak.ttl-seconds:60}}")
  private int defaultTtl;

  /**
   * Derive a stable SHA-256 hash of the token (so we don't log raw tokens).
   */
  private String hash(String token) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] dig = md.digest(Objects.toString(token, "").getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : dig) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      traceSuppressed("ruleBreak.tokenHash", e);
      return "";
    }
  }

  /**
   * Evaluate headers from an incoming HTTP request.
   */
  public RuleBreakContext evaluateFromHeaders(HttpServletRequest req) {
    if (req == null) return RuleBreakContext.inactive();
    String ruleBreakToken = req.getHeader("X-RuleBreak-Token");
    String policyStr = req.getHeader("X-RuleBreak-Policy");
    int ttl = parseInt(req.getHeader("X-RuleBreak-TTL"), defaultTtl);
    String requestId = req.getHeader("X-Request-Id");
    String sessionId = req.getHeader("X-Session-Id");
    return validate(ruleBreakToken, policyStr, ttl, requestId, sessionId);
  }

  /**
   * Evaluate parameters coming from the probe endpoint.
   */
  public RuleBreakContext evaluateFromProbe(boolean flag, String policyStr, int ttl, String token) {
    // Optional flag gate; if explicit false, treat as inactive unless token also authorizes.
    if (!flag) return RuleBreakContext.inactive();
    return validate(token, policyStr, ttl);
  }

  /**
   * Backward-compat signature (without request/session identifiers).
   */
  private RuleBreakContext validate(String token, String policyStr, int ttl) {
    return validate(token, policyStr, ttl, null, null);
  }

  /**
   * Core validator / context builder.
   */
  private RuleBreakContext validate(String token, String policyStr, int ttl, String requestId, String sessionId) {
    String configuredToken = trimToNull(adminToken);
    String requestToken = trimToNull(token);
    String safeRequestId = trimToNull(requestId);
    String safeSessionId = trimToNull(sessionId);
    // Token gating — a request that presents no token is the per-request hot
    // path and stays silent; only presented-token attempts produce a decision.
    if (ConfigValueGuards.isMissing(requestToken)) return RuleBreakContext.inactive();
    if (ConfigValueGuards.isMissing(configuredToken)) {
      traceDecision("rejected", "admin_token_unconfigured", null, safeRequestId, safeSessionId, null);
      return RuleBreakContext.inactive();
    }
    // Preserve every UTF-16 code unit; charset replacement must not alias distinct tokens.
    ByteBuffer configuredBytes = ByteBuffer.allocate(configuredToken.length() * Character.BYTES);
    configuredBytes.asCharBuffer().put(configuredToken);
    ByteBuffer requestBytes = ByteBuffer.allocate(requestToken.length() * Character.BYTES);
    requestBytes.asCharBuffer().put(requestToken);
    if (!MessageDigest.isEqual(configuredBytes.array(), requestBytes.array())) {
      traceDecision("rejected", "token_mismatch", null, safeRequestId, safeSessionId, requestToken);
      return RuleBreakContext.inactive();
    }

    RuleBreakPolicy policy;
    boolean policyFallback = false;
    try {
      String policyName = trimToNull(policyStr);
      policy = policyName == null
          ? RuleBreakPolicy.SAFE_EXPLORE
          : RuleBreakPolicy.valueOf(policyName.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      traceSuppressed("ruleBreak.policy", ex);
      policy = RuleBreakPolicy.SAFE_EXPLORE;
      policyFallback = true;
    }

    Instant expires = Instant.now().plusSeconds(Math.max(1, ttl > 0 ? ttl : defaultTtl));

    traceDecision("activated", policyFallback ? "policy_fallback" : "ok", policy, safeRequestId, safeSessionId, requestToken);
    return RuleBreakContext.active(
        policy,
        hash(requestToken),
        expires,
        safeRequestId,
        safeSessionId
    );
  }

  /**
   * Bounded decision emit for presented-token attempts and activations.
   * Never records raw token/policy text — ids, enum names, reason codes and
   * hashes only.
   */
  private static void traceDecision(String decision, String reason, RuleBreakPolicy policy,
      String requestId, String sessionId, String requestToken) {
    try {
      String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
      TraceStore.put("rulebreak.decision", decision);
      TraceStore.put("rulebreak.decision.reason", safeReason);
      if (policy != null) {
        TraceStore.put("rulebreak.decision.policy", policy.name());
      }
      if (requestId != null) {
        TraceStore.put("rulebreak.decision.requestId", requestId);
      }
      if (sessionId != null) {
        TraceStore.put("rulebreak.decision.sessionIdHash12", SafeRedactor.hash12(sessionId));
      }
      if (requestToken != null) {
        TraceStore.put("rulebreak.decision.tokenHash12", SafeRedactor.hash12(requestToken));
      }
      com.example.lms.search.RequestTrace.emit("guard.rulebreak", "decision",
          "status=" + decision + ";reason=" + safeReason
              + (policy == null ? "" : ";policy=" + policy.name()));
    } catch (Exception e) {
      traceSuppressed("ruleBreak.decision", e);
    }
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static int parseInt(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ex) {
      traceSuppressed("ruleBreak.ttl", ex);
      return fallback;
    }
  }

  private static void traceSuppressed(String stage, Throwable failure) {
    String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
    String errorType = failure == null ? "unknown" : failure.getClass().getSimpleName();
    TraceStore.put("rulebreak.suppressed.stage", safeStage);
    TraceStore.put("rulebreak.suppressed.errorType", errorType);
    TraceStore.put("rulebreak.suppressed." + safeStage, true);
    TraceStore.put("rulebreak.suppressed." + safeStage + ".errorType", errorType);
  }
}
