package com.example.lms.service.search;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaverSearchServiceSecretLabelSourceGuardTest {

    private static final Path SOURCE = Path.of("main/java/com/example/lms/service/NaverSearchService.java");
    private static final Pattern LAST4_SUBSTRING_PATTERN = Pattern.compile(
            "substring\\s*\\([^\\n;]*length\\s*\\(\\s*\\)\\s*-\\s*4",
            Pattern.CASE_INSENSITIVE);

    @Test
    void naverCredentialsAreOnlyUsedAsOutboundHeadersNotDiagnosticLabels() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String lower = source.toLowerCase(Locale.ROOT);

        assertTrue(source.contains("\"X-Naver-Client-Id\""),
                "Naver API client id header must remain for outbound calls");
        assertTrue(source.contains("\"X-Naver-Client-Secret\""),
                "Naver API client secret header must remain for outbound calls");

        assertFalse(source.contains("X-Key-Label"),
                "Naver credentials must not be copied into synthetic key-label headers");
        assertFalse(source.contains("keyLabel"),
                "Naver credentials must not be reduced to diagnostic key labels");
        assertFalse(lower.contains("last4"),
                "Naver credentials must not expose last4-style suffixes");
        assertFalse(LAST4_SUBSTRING_PATTERN.matcher(source).find(),
                "Naver credentials must not be truncated with length()-4 suffix labels");

        String sensitiveHeaderMethod = methodBody(source,
                "private static boolean isSensitiveHeader",
                "private static String safeHeaderValueForLog");
        assertTrue(sensitiveHeaderMethod.contains("n.contains(\"key-label\")"),
                "key-label must remain classified as a sensitive header");
        assertTrue(sensitiveHeaderMethod.contains("n.contains(\"keylabel\")"),
                "keylabel must remain classified as a sensitive header");

        String outsideSensitiveHeader = source.replace(sensitiveHeaderMethod, "").toLowerCase(Locale.ROOT);
        assertFalse(outsideSensitiveHeader.contains("key-label"),
                "key-label must only appear in the sensitive-header denylist");
        assertFalse(outsideSensitiveHeader.contains("keylabel"),
                "keylabel must only appear in the sensitive-header denylist");
    }

    @Test
    void constructorBridgeAndDisabledLogKeepOnlyCredentialPresenceBooleans() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("@Value(\"${naver.keys:${NAVER_KEYS:}}\")"),
                "constructor must preserve naver.keys -> NAVER_KEYS resolution");
        assertTrue(source.contains("@Value(\"${naver.client-id:${NAVER_CLIENT_ID:}}\")"),
                "constructor must preserve NAVER_CLIENT_ID bridge fallback");
        assertTrue(source.contains("@Value(\"${naver.client-secret:${NAVER_CLIENT_SECRET:}}\")"),
                "constructor must preserve NAVER_CLIENT_SECRET bridge fallback");

        assertTrue(source.contains("private final boolean naverKeysPresent;"),
                "diagnostics should retain only naver.keys presence");
        assertTrue(source.contains("private final boolean naverClientPairPresent;"),
                "diagnostics should retain only client pair presence");
        assertFalse(source.contains("private final String naverClientId;"),
                "raw Naver client id must not be retained for diagnostics");
        assertFalse(source.contains("private final String naverClientSecret;"),
                "raw Naver client secret must not be retained for diagnostics");
        assertFalse(source.contains("this.naverClientId ="),
                "raw Naver client id must not be assigned to a field");
        assertFalse(source.contains("this.naverClientSecret ="),
                "raw Naver client secret must not be assigned to a field");
        assertFalse(source.contains("new NaverCredentialBridge.Credential(naverClientId"),
                "fallback credentials should come from the resolved secret-safe bridge only");
        assertTrue(source.contains("keyResolverProvider.getIfAvailable()"),
                "NaverSearchService should resolve the optional central KeyResolver provider");
        assertTrue(source.contains("injectedKeyResolver.resolveNaverKeysCsvSafe()"),
                "NaverSearchService should use the central KeyResolver Naver ladder when available");
        assertTrue(source.contains("NaverCredentialBridge.resolveKeysCsv(naverKeysCsv, naverClientId, naverClientSecret)"),
                "direct bridge resolution should remain only as the fallback when KeyResolver is unavailable");

        String disabledLog = methodBody(source,
                "private boolean hasCreds()",
                "private String naverDisabledReason()");
        assertTrue(disabledLog.contains("keysPresent={}"),
                "disabled log should expose key presence as a boolean only");
        assertTrue(disabledLog.contains("clientPairPresent={}"),
                "disabled log should expose client pair presence as a boolean only");
        assertFalse(disabledLog.contains("naverClientId"),
                "disabled diagnostics must not reference raw client id");
        assertFalse(disabledLog.contains("naverClientSecret"),
                "disabled diagnostics must not reference raw client secret");
    }

    private static String methodBody(String source, String startMarker, String nextMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(nextMarker, start + startMarker.length());
        assertTrue(start >= 0, "missing source marker: " + startMarker);
        assertTrue(end > start, "missing next source marker: " + nextMarker);
        return source.substring(start, end);
    }
}
