package com.example.lms.service;

import dev.langchain4j.data.document.Document;

import com.example.lms.dto.RagEvidenceMetadata;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class NoEvidenceChatFallback {

    private static final String LOCAL_FALLBACK_NOTICE =
            "\uAE30\uBCF8 \uBAA8\uB378 \uC751\uB2F5\uC774 \uC9C0\uAE08 "
                    + "\uC548\uC815\uC801\uC73C\uB85C \uC0DD\uC131\uB418\uC9C0 "
                    + "\uC54A\uC544 \uB85C\uCEEC \uC548\uC804 \uC751\uB2F5\uC73C\uB85C "
                    + "\uBA3C\uC800 \uC548\uB0B4\uB4DC\uB9BD\uB2C8\uB2E4.";
    private static final String OFFICIAL_EVIDENCE_NEEDED_NOTICE =
            "evidence_needed: official/changelog evidence is missing from the current runtime answer path.";

    private NoEvidenceChatFallback() {
    }

    static boolean hasNoEvidence(Collection<?>... sources) {
        if (sources == null || sources.length == 0) {
            return true;
        }
        for (Collection<?> source : sources) {
            if (source != null && !source.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static String compose(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            return "\uC694\uCCAD \uB0B4\uC6A9\uC744 \uC785\uB825\uD574 \uC8FC\uC138\uC694. "
                    + LOCAL_FALLBACK_NOTICE
                    + " \uC9C8\uBB38\uC744 \uBCF4\uB0B4\uBA74 \uCC98\uB9AC \uC0C1\uD0DC\uC640 "
                    + "\uB2E4\uC74C \uB2E8\uACC4\uB97C \uD568\uAED8 \uC548\uB0B4\uD558\uACA0\uC2B5\uB2C8\uB2E4.";
        }
        if (officialEvidenceNeededIntent(normalized)) {
            TraceStore.put("chat.llmFallback.evidenceNeeded", true);
            TraceStore.put("chat.llmFallback.evidenceNeededReason", "official_evidence_missing");
            return OFFICIAL_EVIDENCE_NEEDED_NOTICE;
        }
        String selfAskRewrite = selfAskRewriteFallbackOrNull(normalized);
        if (selfAskRewrite != null) {
            return selfAskRewrite;
        }
        if (isGreeting(normalized)) {
            return "\uC548\uB155\uD558\uC138\uC694. " + LOCAL_FALLBACK_NOTICE
                    + " \uBB34\uC5C7\uC744 \uB3C4\uC640\uB4DC\uB9B4\uAE4C\uC694?";
        }
        return LOCAL_FALLBACK_NOTICE
                + " \uC9C8\uBB38\uC740 \uC811\uC218\uD588\uC2B5\uB2C8\uB2E4. "
                + "\uD604\uC7AC \uC0AC\uC6A9\uD560 \uADFC\uAC70\uAC00 \uC5C6\uC5B4 "
                + "\uD655\uC815 \uB2F5\uBCC0\uC740 \uC81C\uD55C\uB429\uB2C8\uB2E4."
                + " \uC6D0\uD558\uB294 \uCD9C\uB825 \uD615\uC2DD\uC774\uB098 "
                + "\uCD94\uAC00 \uADFC\uAC70\uB97C \uC54C\uB824\uC8FC\uC2DC\uBA74 "
                + "\uC774\uC5B4\uC11C \uB3C4\uC640\uB4DC\uB9AC\uACA0\uC2B5\uB2C8\uB2E4.";
    }

    static String selfAskRewriteFallbackOrNull(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        String compact = normalized.replaceAll("\\s+", "");
        boolean requestsSelfAsk = normalized.contains("self-ask")
                || normalized.contains("self ask")
                || normalized.contains("\uC140\uD504\uC560\uC2A4\uD06C");
        boolean rejectsSelfAsk = normalized.contains("do not use self-ask")
                || normalized.contains("do not use self ask")
                || normalized.contains("without self-ask")
                || normalized.contains("without self ask")
                || normalized.contains("\uC140\uD504\uC560\uC2A4\uD06C\uB97C \uC0AC\uC6A9\uD558\uC9C0")
                || normalized.contains("\uC140\uD504\uC560\uC2A4\uD06C \uC5C6\uC774");
        boolean requestsRewrite = normalized.contains("rewrite")
                || normalized.contains("\uC7AC\uC791\uC131")
                || normalized.contains("\uC9C8\uC758\uB97C \uB2E4\uC2DC")
                || normalized.contains("\uC9C8\uBB38\uC744 \uB2E4\uC2DC");
        boolean requestsThreeOptions = compact.contains("a/b/c");
        if (!requestsSelfAsk || rejectsSelfAsk || !requestsRewrite || !requestsThreeOptions) {
            return null;
        }

        TraceStore.put("chat.llmFallback.mode", "self_ask_query_rewrite");
        return "Self-Ask: \uC2E4\uD589 \uB300\uC0C1 \uAE30\uB2A5, \uC131\uACF5 \uAE30\uC900, \uD5C8\uC6A9 \uB3C4\uAD6C\uAC00 \uBD80\uC871\uD569\uB2C8\uB2E4.\n"
                + "A. \uBCF4\uC218\uC801 \uC7AC\uC791\uC131: \uD558\uB098\uC758 \uAE30\uB2A5\uACFC \uAC80\uC99D \uAE30\uC900\uC744 \uC9C0\uC815\uD574 \uC7AC\uD604 \uD6C4 \uCD5C\uC18C \uC218\uC815\uC744 \uC694\uCCAD\uD55C\uB2E4.\n"
                + "B. \uD0D0\uC0C9\uC801 \uC7AC\uC791\uC131: \uC0AC\uC6A9 \uAC00\uB2A5\uD55C \uAE30\uB2A5 \uC138 \uAC00\uC9C0\uB97C \uC81C\uC548\uD558\uACE0 \uC2FC \uBC29\uBC95\uC744 \uC120\uD0DD\uD574 \uAC80\uC99D\uD55C\uB2E4.\n"
                + "C. \uBC18\uC99D \uC6B0\uC120 \uC7AC\uC791\uC131: \uAE30\uB300 \uACB0\uACFC\uC640 \uC2E4\uD328 \uC870\uAC74\uC744 \uBA3C\uC800 \uC815\uD558\uACE0 \uAC00\uC7A5 \uC791\uC740 \uBC18\uB840\uBD80\uD130 \uAC80\uC99D\uD55C\uB2E4.";
    }

    static ChatResult orEvidenceFallback(
            String query,
            String modelUsed,
            boolean ragUsed,
            Collection<?> topDocs,
            Collection<?> vectorDocs,
            String evidenceFallback) {
        return orEvidenceFallback(query, modelUsed, ragUsed, topDocs, vectorDocs, null, evidenceFallback);
    }

    static ChatResult orEvidenceFallback(
            String query,
            String modelUsed,
            boolean ragUsed,
            Collection<?> topDocs,
            Collection<?> vectorDocs,
            Collection<?> localDocs,
            String evidenceFallback) {
        return orEvidenceFallback(query, modelUsed, ragUsed, topDocs, vectorDocs, localDocs, evidenceFallback, List.of());
    }

    static ChatResult orEvidenceFallback(
            String query,
            String modelUsed,
            boolean ragUsed,
            Collection<?> topDocs,
            Collection<?> vectorDocs,
            Collection<?> localDocs,
            String evidenceFallback,
            Collection<RagEvidenceMetadata> evidenceMetadata) {
        List<RagEvidenceMetadata> safeMetadata = safeEvidenceMetadata(evidenceMetadata);
        if (officialEvidenceNeededIntent(query)) {
            Set<String> officialSourceHosts = officialEvidenceSourceHosts(safeMetadata);
            List<String> missingExplicitDomains = missingExplicitOfficialDomains(
                    query,
                    officialSourceHosts);
            if (!hasRequestedOfficialEvidence(query, officialSourceHosts, missingExplicitDomains)) {
                TraceStore.put("chat.llmFallback.evidenceNeeded", true);
                TraceStore.put("chat.llmFallback.evidenceNeededReason", "official_evidence_missing");
                TraceStore.put("chat.llmFallback.evidenceNeededMissingHostCount", missingExplicitDomains.size());
                return ChatResult.of(
                        officialEvidenceNeededNotice(missingExplicitDomains),
                        modelUsed + ":fallback:local-lite",
                        false);
            }
        }
        if (hasNoAnswerEvidence(topDocs, vectorDocs, localDocs) && safeMetadata.isEmpty()) {
            TraceStore.put("chat.llmFallback.mode", "local_lite_no_evidence");
            return ChatResult.of(compose(query), modelUsed + ":fallback:local-lite", false);
        }
        TraceStore.put("chat.llmFallback.mode", "evidence");
        String safeFallback = evidenceFallback == null ? "" : evidenceFallback.trim();
        if (safeFallback.isBlank()) {
            safeFallback = composeCompactEvidenceFallback(topDocs, vectorDocs, localDocs);
            TraceStore.put("chat.llmFallback.blankEvidenceFallbackRecovered", !safeFallback.isBlank());
        }
        return ChatResult.of(
                safeFallback,
                modelUsed + ":fallback:evidence",
                ragUsed,
                fallbackEvidenceKinds(topDocs, vectorDocs, localDocs, safeMetadata),
                safeMetadata);
    }

    private static String composeCompactEvidenceFallback(
            Collection<?> topDocs,
            Collection<?> vectorDocs,
            Collection<?> localDocs) {
        StringBuilder out = new StringBuilder();
        out.append("\uAE30\uBCF8 \uBAA8\uB378 \uC751\uB2F5\uC774 \uC548\uC815\uC801\uC73C\uB85C ")
                .append("\uC0DD\uC131\uB418\uC9C0 \uC54A\uC544 \uAC80\uC0C9 \uADFC\uAC70\uB9CC ")
                .append("\uC9E7\uAC8C \uC815\uB9AC\uD569\uB2C8\uB2E4.\n");
        int count = 0;
        count = appendEvidence(out, "WEB", topDocs, count, 4);
        count = appendEvidence(out, "RAG", vectorDocs, count, 6);
        appendEvidence(out, "LOCAL", localDocs, count, 8);
        return out.toString().trim();
    }

    private static int appendEvidence(
            StringBuilder out,
            String label,
            Collection<?> docs,
            int count,
            int max) {
        if (out == null || docs == null || docs.isEmpty() || count >= max) {
            return count;
        }
        for (Object doc : docs) {
            if (count >= max) {
                break;
            }
            String text = evidenceText(doc);
            if (text == null || text.isBlank()) {
                continue;
            }
            count++;
            out.append("- [").append(label).append(count).append("] ")
                    .append(truncateEvidenceText(text, 220))
                    .append('\n');
        }
        return count;
    }

    private static String evidenceText(Object candidate) {
        if (candidate == null) {
            return "";
        }
        if (candidate instanceof Document doc) {
            return doc.text();
        }
        if (candidate instanceof dev.langchain4j.rag.content.Content content) {
            try {
                return content.textSegment() == null ? "" : content.textSegment().text();
            } catch (Exception failure) {
                traceEvidenceTextSuppressed("evidenceText.textSegment", failure);
                return String.valueOf(candidate);
            }
        }
        return String.valueOf(candidate);
    }

    private static boolean officialEvidenceNeededIntent(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (q.contains("evidence_needed")) {
            return true;
        }
        boolean namedOfficialTarget = q.contains("openai")
                || q.contains("supabase")
                || !requestedOfficialSourceDomains(q).isEmpty();
        boolean evidenceScoped = q.contains("evidence")
                || q.contains("citation")
                || q.contains("source")
                || q.contains("\uADFC\uAC70")
                || q.contains("\uCD9C\uCC98");
        boolean officialOrFresh = q.contains("official")
                || q.contains("changelog")
                || q.contains("release notes")
                || q.contains("latest")
                || q.contains("current")
                || q.contains("\uACF5\uC2DD")
                || q.contains("\uCD5C\uC2E0")
                || q.contains("\uBCC0\uACBD")
                || q.contains("\uC5C5\uB370\uC774\uD2B8");
        return namedOfficialTarget && evidenceScoped && officialOrFresh;
    }

    private static String officialEvidenceNeededNotice(Collection<String> missingExplicitDomains) {
        if (missingExplicitDomains == null || missingExplicitDomains.isEmpty()) {
            return OFFICIAL_EVIDENCE_NEEDED_NOTICE;
        }
        StringBuilder out = new StringBuilder(OFFICIAL_EVIDENCE_NEEDED_NOTICE);
        for (String domain : missingExplicitDomains) {
            if (domain != null && !domain.isBlank()) {
                out.append("\n- ").append(domain).append(": EVIDENCE_NEEDED");
            }
        }
        return out.toString();
    }

    private static String officialEvidenceCorpus(
            Collection<?> topDocs,
            Collection<?> vectorDocs,
            Collection<?> localDocs,
            Collection<RagEvidenceMetadata> evidenceMetadata) {
        String evidence = evidenceCorpus(topDocs, vectorDocs, localDocs);
        if (evidenceMetadata != null && !evidenceMetadata.isEmpty()) {
            evidence = evidence + " " + evidenceMetadataCorpus(evidenceMetadata);
        }
        return evidence;
    }

    private static Set<String> officialEvidenceSourceHosts(Collection<RagEvidenceMetadata> evidenceMetadata) {
        Set<String> hosts = new LinkedHashSet<>();
        if (evidenceMetadata == null || evidenceMetadata.isEmpty()) {
            return hosts;
        }
        for (RagEvidenceMetadata item : evidenceMetadata) {
            if (item == null) {
                continue;
            }
            addOfficialEvidenceSourceHost(hosts, item.source());
            addOfficialEvidenceSourceHost(hosts, item.filePath());
        }
        return hosts;
    }

    private static void addOfficialEvidenceSourceHost(Set<String> hosts, String source) {
        if (hosts == null || source == null || source.isBlank()) {
            return;
        }
        try {
            String host = java.net.URI.create(source.trim()).getHost();
            if (host != null && !host.isBlank()) {
                hosts.add(host.toLowerCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException ignored) {
            // Fail closed: malformed source metadata is not official-host evidence.
        }
    }

    private static List<String> missingExplicitOfficialDomains(String query, Collection<String> sourceHosts) {
        Collection<String> safeHosts = sourceHosts == null ? Set.of() : sourceHosts;
        Set<String> missing = new LinkedHashSet<>();
        for (String domain : requestedOfficialSourceDomains(query)) {
            if (!safeHosts.contains(domain)) {
                missing.add(domain);
            }
        }
        return List.copyOf(missing);
    }

    private static List<String> requestedOfficialSourceDomains(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        q = q.replaceAll(
                        "(?<![\\p{L}]\\.[\\p{L}])(?<=[\\p{L}\\p{N}])[.!?\\u3002\\uFF01\\uFF1F][\\\"'\\p{Pe}\\p{Pf}]*(?=[\\s\\p{Z}]+)",
                        ";")
                .replaceAll("(?<=[\\p{L}\\p{N}])[\\\"'\\p{Pe}\\p{Pf}]+[.!?\\u3002\\uFF01\\uFF1F](?=[\\s\\p{Z}]+)", ";")
                .replaceAll("(?<=[\\p{L}\\p{N}])[\\u3002\\uFF01\\uFF1F][\\\"'\\p{Pe}\\p{Pf}]*(?=[\\p{L}\\p{N}])", ";")
                .replaceAll("(?<=[\\p{L}\\p{N}])[\\\"'\\p{Pe}\\p{Pf}]+[\\u3002\\uFF01\\uFF1F](?=[\\p{L}\\p{N}])", ";");
        Set<String> domains = new LinkedHashSet<>();
        addDomainsFromOfficialSourceContext(
                domains,
                q,
                "\\bofficial\\b([^;\\n]{0,320}?)\\b(?:sources?|domains?|evidence)\\b");
        addDomainsFromOfficialSourceContext(
                domains,
                q,
                "\\bofficial\\s+(?:sources?|domains?|evidence)\\b([^;\\n]{0,320})");
        addDomainsFromOfficialSourceContext(
                domains,
                q,
                "(?:^|[;\\n])([^;\\n]{0,320}?)\\bofficial\\s+(?:sources?|domains?|evidence)\\b");
        addDomainsFromOfficialSourceContext(
                domains,
                q,
                "(?:^|[;\\n])([^;\\n]{0,320}?)\uACF5\uC2DD\\s*(?:\uCD9C\uCC98|\uADFC\uAC70|\uBB38\uC11C)");
        addDomainsFromOfficialSourceContext(
                domains,
                q,
                "\uACF5\uC2DD\\s*(?:\uCD9C\uCC98|\uADFC\uAC70|\uBB38\uC11C)([^;\\n]{0,320})");
        if (domains.isEmpty() && q.contains("evidence_needed")
                && (q.contains("official") || q.contains("\uACF5\uC2DD"))) {
            addDomains(domains, q);
        }
        return List.copyOf(domains);
    }

    private static void addDomainsFromOfficialSourceContext(
            Set<String> domains,
            String query,
            String contextPattern) {
        java.util.regex.Matcher contextMatcher = java.util.regex.Pattern
                .compile(contextPattern)
                .matcher(query);
        while (contextMatcher.find()) {
            addDomains(domains, contextMatcher.group(1));
        }
    }

    private static void addDomains(Set<String> domains, String text) {
        if (domains == null || text == null || text.isBlank()) {
            return;
        }
        java.util.regex.Matcher domainMatcher = java.util.regex.Pattern
                .compile("(?<![a-z0-9-])((?:[a-z0-9][a-z0-9-]*\\.)+(?:com|org|net|io|ai|dev|co|kr|edu|gov))(?![a-z0-9-]|\\.[a-z0-9-])")
                .matcher(text);
        while (domainMatcher.find()) {
            domains.add(domainMatcher.group(1));
        }
    }

    private static boolean hasRequestedOfficialEvidence(
            String query,
            Collection<String> officialSourceHosts,
            Collection<String> missingExplicitDomains) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        boolean needsOpenAi = q.contains("openai");
        boolean needsSupabase = q.contains("supabase");
        if (missingExplicitDomains != null && !missingExplicitDomains.isEmpty()) {
            return false;
        }
        boolean hasOpenAi = hasDomainOrSubdomain(officialSourceHosts, "openai.com");
        boolean hasSupabase = hasDomainOrSubdomain(officialSourceHosts, "supabase.com");
        return (!needsOpenAi || hasOpenAi) && (!needsSupabase || hasSupabase);
    }

    private static boolean hasDomainOrSubdomain(Collection<String> domains, String expectedDomain) {
        if (domains == null || expectedDomain == null || expectedDomain.isBlank()) {
            return false;
        }
        for (String domain : domains) {
            if (expectedDomain.equals(domain) || (domain != null && domain.endsWith("." + expectedDomain))) {
                return true;
            }
        }
        return false;
    }

    private static String evidenceMetadataCorpus(Collection<RagEvidenceMetadata> evidenceMetadata) {
        if (evidenceMetadata == null || evidenceMetadata.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (RagEvidenceMetadata item : evidenceMetadata) {
            if (item == null) {
                continue;
            }
            appendMetadataPart(out, item.title());
            appendMetadataPart(out, item.source());
            appendMetadataPart(out, item.filePath());
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static void appendMetadataPart(StringBuilder out, String value) {
        if (out == null || value == null || value.isBlank()) {
            return;
        }
        out.append(' ').append(value);
    }

    private static String evidenceCorpus(Collection<?>... sources) {
        if (sources == null || sources.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Collection<?> source : sources) {
            if (source == null || source.isEmpty()) {
                continue;
            }
            for (Object candidate : source) {
                String text = evidenceText(candidate);
                if (text != null && !text.isBlank()) {
                    out.append(' ').append(text.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out.toString();
    }

    private static void traceEvidenceTextSuppressed(String stage, Exception failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
        TraceStore.inc("chat.llmFallback.evidenceText.suppressed.count");
        TraceStore.put("chat.llmFallback.evidenceText.suppressed.stage", safeStage);
        TraceStore.put("chat.llmFallback.evidenceText.suppressed.errorType", errorType);
    }

    private static String truncateEvidenceText(String text, int maxChars) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
    }

    private static Set<String> fallbackEvidenceKinds(
            Collection<?> topDocs,
            Collection<?> vectorDocs,
            Collection<?> localDocs) {
        return fallbackEvidenceKinds(topDocs, vectorDocs, localDocs, List.of());
    }

    private static Set<String> fallbackEvidenceKinds(
            Collection<?> topDocs,
            Collection<?> vectorDocs,
            Collection<?> localDocs,
            Collection<RagEvidenceMetadata> evidenceMetadata) {
        Set<String> kinds = new LinkedHashSet<>();
        if (topDocs != null && !topDocs.isEmpty()) {
            kinds.add("WEB");
        }
        if (vectorDocs != null && !vectorDocs.isEmpty()) {
            kinds.add("RAG");
        }
        if (hasUserFacingLocalEvidence(localDocs)) {
            kinds.add("LOCAL");
        }
        if (evidenceMetadata != null) {
            for (RagEvidenceMetadata item : evidenceMetadata) {
                if (item == null || item.kind() == null || item.kind().isBlank()) {
                    continue;
                }
                kinds.add(item.kind().toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(kinds);
    }

    private static List<RagEvidenceMetadata> safeEvidenceMetadata(Collection<RagEvidenceMetadata> evidenceMetadata) {
        if (evidenceMetadata == null || evidenceMetadata.isEmpty()) {
            return List.of();
        }
        return evidenceMetadata.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    private static boolean hasNoAnswerEvidence(Collection<?> topDocs, Collection<?> vectorDocs, Collection<?> localDocs) {
        return hasNoEvidence(topDocs, vectorDocs) && !hasUserFacingLocalEvidence(localDocs);
    }

    private static boolean hasUserFacingLocalEvidence(Collection<?> localDocs) {
        if (localDocs == null || localDocs.isEmpty()) {
            return false;
        }
        int supportingOnlyCount = 0;
        for (Object candidate : localDocs) {
            String text = localDocText(candidate);
            if (text == null || text.isBlank()) {
                continue;
            }
            if (isAgentVisibleDebugEvidence(text)) {
                supportingOnlyCount++;
                continue;
            }
            return true;
        }
        if (supportingOnlyCount > 0) {
            TraceStore.put("chat.llmFallback.supportingOnlyLocalDocs", supportingOnlyCount);
        }
        return false;
    }

    private static boolean isAgentVisibleDebugEvidence(String text) {
        if (text == null) {
            return false;
        }
        return text.stripLeading().startsWith("AGENT_VISIBLE_DEBUG_HEARTBEAT");
    }

    private static String localDocText(Object candidate) {
        if (candidate == null) {
            return "";
        }
        if (candidate instanceof Document doc) {
            return doc.text();
        }
        return String.valueOf(candidate);
    }

    private static boolean isGreeting(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(hi|hello|hey)\\b.*")
                || lower.contains("\uC548\uB155")
                || containsGreetingToken(lower, "\uD558\uC774")
                || containsGreetingToken(lower, "\uD5EC\uB85C")
                || lower.contains("\uBC18\uAC00");
    }

    private static boolean containsGreetingToken(String text, String token) {
        int from = 0;
        while (from < text.length()) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return false;
            }
            int before = at - 1;
            int after = at + token.length();
            boolean leftBoundary = before < 0 || !Character.isLetterOrDigit(text.charAt(before));
            boolean rightBoundary = after >= text.length() || !Character.isLetterOrDigit(text.charAt(after));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = at + token.length();
        }
        return false;
    }
}
