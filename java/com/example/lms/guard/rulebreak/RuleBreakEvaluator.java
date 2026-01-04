package com.example.lms.guard.rulebreak;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.math.NumberUtils;
import java.time.Instant;
import java.util.Objects;
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

  @Value("${tools.rulebreak.admin-token:}")
  private String adminToken;

  @Value("${tools.rulebreak.ttl-seconds:60}")
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
      return "";
    }
  }

  /**
   * Evaluate headers from an incoming HTTP request.
   */
  public RuleBreakContext evaluateFromHeaders(HttpServletRequest req) {
    if (req == null) return RuleBreakContext.inactive();
    String token = req.getHeader("X-RuleBreak-Token");
    String policyStr = req.getHeader("X-RuleBreak-Policy");
    int ttl = NumberUtils.toInt(req.getHeader("X-RuleBreak-TTL"), defaultTtl);
    String requestId = req.getHeader("X-Request-Id");
    String sessionId = req.getHeader("X-Session-Id");
    return validate(token, policyStr, ttl, requestId, sessionId);
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
    // Token gating
    if (adminToken == null || adminToken.isEmpty()) return RuleBreakContext.inactive();
    if (token == null || !token.equals(adminToken)) return RuleBreakContext.inactive();

    RuleBreakPolicy policy;
    try {
      policy = (policyStr == null || policyStr.isEmpty())
          ? RuleBreakPolicy.SAFE_EXPLORE
          : RuleBreakPolicy.valueOf(policyStr);
    } catch (IllegalArgumentException ex) {
      policy = RuleBreakPolicy.SAFE_EXPLORE;
    }

    Instant expires = Instant.now().plusSeconds(Math.max(1, ttl > 0 ? ttl : defaultTtl));

    return RuleBreakContext.builder()
        .active(true)
        .policy(policy)
        .tokenHash(hash(token))
        .expiresAt(expires)
        .requestId(requestId)
        .sessionId(sessionId)
        .build();
  }
}