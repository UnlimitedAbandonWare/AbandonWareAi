package ai.abandonware.nova.orch.aop;

import com.example.lms.trace.SafeRedactor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WebAwaitEventsSummary {

    private static final String SUMMARY_PREFIX = "web.await.events.summary.";
    private static final String SKIPPED_PREFIX = "web.await.skipped.";

    private WebAwaitEventsSummary() {
    }

    static Map<String, Object> buildTraceEntries(
            @Nullable List<Map<String, Object>> events,
            @Nullable Map<String, Object> skippedCtx) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean hasEvents = events != null && !events.isEmpty();
        boolean hasSkipped = skippedCtx != null && !skippedCtx.isEmpty();
        if (!hasEvents && !hasSkipped) {
            return out;
        }

        if (hasEvents) {
            appendEventSummary(out, events);
        } else {
            appendEmptyEventSummary(out);
        }
        if (hasSkipped) {
            appendSkippedSummary(out, skippedCtx);
        }
        return out;
    }

    private static void appendEventSummary(Map<String, Object> out, List<Map<String, Object>> events) {
        List<Map<String, Object>> simplified = new ArrayList<>();
        for (Map<String, Object> ev : events) {
            if (ev == null) {
                continue;
            }
            Map<String, Object> m2 = new LinkedHashMap<>();
            Object engine = firstNonNull(ev.get("engine"), ev.get("provider"));
            Object stage = firstNonNull(ev.get("stage"), ev.get("kind"));
            Object step = firstNonNull(ev.get("step"), ev.get("op"), ev.get("kind2"), ev.get("phase"));
            Object cause = firstNonNull(ev.get("cause"), ev.get("reason"), ev.get("status"));
            Object waitedMs = ev.get("waitedMs");
            Object timeoutMs = ev.get("timeoutMs");
            Object skip = ev.get("skip");
            Object timeout = ev.get("timeout");
            Object softTimeout = ev.get("softTimeout");
            Object hardTimeout = ev.get("hardTimeout");
            Object nonOk = ev.get("nonOk");

            if (engine != null) {
                m2.put("engine", SafeRedactor.traceLabel(engine));
            }
            if (stage != null) {
                m2.put("stage", SafeRedactor.traceLabel(stage));
            }
            if (step != null) {
                m2.put("step", SafeRedactor.traceLabel(step));
            }
            if (cause != null) {
                m2.put("cause", SafeRedactor.traceLabel(cause));
            }
            if (waitedMs != null) {
                m2.put("waitedMs", waitedMs);
            }
            if (timeoutMs != null) {
                m2.put("timeoutMs", timeoutMs);
            }
            if (skip != null) {
                m2.put("skip", skip);
            }
            if (timeout != null) {
                m2.put("timeout", timeout);
            }
            if (softTimeout != null) {
                m2.put("softTimeout", softTimeout);
            }
            if (hardTimeout != null) {
                m2.put("hardTimeout", hardTimeout);
            }
            if (nonOk != null) {
                m2.put("nonOk", nonOk);
            }
            simplified.add(m2);
        }

        out.put(SUMMARY_PREFIX + "count", simplified.size());

        long nonOkCount = 0L;
        long timeoutCount = 0L;
        long softTimeoutCount = 0L;
        long hardTimeoutCount = 0L;
        long skipCount = 0L;
        long interruptedCount = 0L;
        long budgetExhaustedCount = 0L;
        long missingFutureCount = 0L;
        long awaitTimeoutCount = 0L;
        long intentionalCancelWaitedMsZeroCount = 0L;
        LinkedHashSet<String> intentionalCancelWaitedMsZeroEngines = new LinkedHashSet<>();
        long maxWaited = 0L;
        long maxTimeout = 0L;
        LinkedHashSet<String> engines = new LinkedHashSet<>();
        Map<String, Integer> engineCounts = new LinkedHashMap<>();
        Map<String, Integer> causeCounts = new LinkedHashMap<>();
        Map<String, Integer> stepCounts = new LinkedHashMap<>();
        Map<String, Integer> engineCauseCounts = new LinkedHashMap<>();
        List<String> digests = new ArrayList<>();

        for (Map<String, Object> ev : simplified) {
            String eng = stringOrDefault(ev.get("engine"), "?");
            String stageS = stringOrDefault(ev.get("stage"), "");
            String stepS = stringOrDefault(ev.get("step"), "");
            String causeS = stringOrDefault(ev.get("cause"), "");

            engines.add(eng);
            engineCounts.put(eng, engineCounts.getOrDefault(eng, 0) + 1);
            if (!stepS.isBlank()) {
                stepCounts.put(stepS, stepCounts.getOrDefault(stepS, 0) + 1);
            }
            if (!causeS.isBlank()) {
                causeCounts.put(causeS, causeCounts.getOrDefault(causeS, 0) + 1);
                String ec = eng + "|" + causeS;
                engineCauseCounts.put(ec, engineCauseCounts.getOrDefault(ec, 0) + 1);
            }

            boolean nonOk = isTruthy(ev.get("nonOk"));
            boolean timeout = isTruthy(ev.get("timeout"));
            boolean skip = isTruthy(ev.get("skip"));
            boolean softTimeout = isTruthy(ev.get("softTimeout"));
            boolean hardTimeout = isTruthy(ev.get("hardTimeout"));

            String causeLower = causeS.toLowerCase(Locale.ROOT);
            boolean awaitTimeout = isAwaitTimeout(causeLower);
            if (awaitTimeout) {
                awaitTimeoutCount++;
            }
            if ("budget_exhausted".equals(causeLower)) {
                budgetExhaustedCount++;
            }
            if ("missing_future".equals(causeLower)) {
                missingFutureCount++;
                skip = true;
            }
            if ("interrupted".equals(causeLower)) {
                interruptedCount++;
            }
            if (causeLower.startsWith("skip_")) {
                skip = true;
            }

            if (!awaitTimeout && !softTimeout && !hardTimeout) {
                boolean stageSoft = "soft".equalsIgnoreCase(stageS);
                boolean stageHard = "hard".equalsIgnoreCase(stageS);
                if ("budget_exhausted".equals(causeLower) || "timeout_soft".equals(causeLower)) {
                    softTimeout = true;
                } else if ("timeout_hard".equals(causeLower)) {
                    hardTimeout = true;
                } else if (causeLower.contains("timeout")) {
                    if (stageSoft) {
                        softTimeout = true;
                    } else if (stageHard) {
                        hardTimeout = true;
                    } else {
                        hardTimeout = true;
                    }
                }
            }

            if (!awaitTimeout && (causeLower.contains("timeout") || "budget_exhausted".equals(causeLower))) {
                timeout = true;
            }

            boolean timeoutAny = timeout || softTimeout || hardTimeout;
            if (timeoutAny) {
                if (softTimeout) {
                    softTimeoutCount++;
                } else if (hardTimeout) {
                    hardTimeoutCount++;
                } else if ("soft".equalsIgnoreCase(stageS)) {
                    softTimeoutCount++;
                } else if ("hard".equalsIgnoreCase(stageS)) {
                    hardTimeoutCount++;
                }
            }

            if (nonOk) {
                nonOkCount++;
            }
            if (timeoutAny) {
                timeoutCount++;
            }
            if (skip) {
                skipCount++;
            }

            long waited = toLong(ev.get("waitedMs"));
            long timeoutMs = toLong(ev.get("timeoutMs"));
            if (waited == 0L && ("intentional_cancel".equals(causeLower)
                    || "interrupted".equals(causeLower)
                    || causeLower.contains("cancel"))) {
                intentionalCancelWaitedMsZeroCount++;
                intentionalCancelWaitedMsZeroEngines.add(eng);
            }
            if (waited > 0L) {
                maxWaited = Math.max(maxWaited, waited);
            }
            if (timeoutMs > 0L) {
                maxTimeout = Math.max(maxTimeout, timeoutMs);
            }

            digests.add(digest(eng, stageS, stepS, causeS, waited, timeoutMs,
                    skip, timeout, awaitTimeout, softTimeout, hardTimeout, nonOk));
        }

        out.put(SUMMARY_PREFIX + "engines", String.join(",", engines));
        out.put(SUMMARY_PREFIX + "nonOk.count", nonOkCount);
        out.put(SUMMARY_PREFIX + "timeout.count", timeoutCount);
        out.put(SUMMARY_PREFIX + "timeout.soft.count", softTimeoutCount);
        out.put(SUMMARY_PREFIX + "timeout.hard.count", hardTimeoutCount);
        out.put(SUMMARY_PREFIX + "skip.count", skipCount);
        out.put(SUMMARY_PREFIX + "interrupted.count", interruptedCount);
        out.put(SUMMARY_PREFIX + "budget_exhausted.count", budgetExhaustedCount);
        out.put(SUMMARY_PREFIX + "missing_future.count", missingFutureCount);
        out.put(SUMMARY_PREFIX + "await_timeout.count", awaitTimeoutCount);
        out.put(SUMMARY_PREFIX + "intentional_cancel.waitedMs0.count", intentionalCancelWaitedMsZeroCount);
        if (!intentionalCancelWaitedMsZeroEngines.isEmpty()) {
            out.put(SUMMARY_PREFIX + "intentional_cancel.waitedMs0.engines",
                    String.join(",", intentionalCancelWaitedMsZeroEngines));
        }
        out.put(SUMMARY_PREFIX + "maxWaitedMs", maxWaited);
        out.put(SUMMARY_PREFIX + "maxTimeoutMs", maxTimeout);

        appendCountEntries(out, SUMMARY_PREFIX + "engine.", ".count", engineCounts, engineCounts.size());
        appendCountEntries(out, SUMMARY_PREFIX + "cause.", ".count", causeCounts, 12);
        appendCountEntries(out, SUMMARY_PREFIX + "step.", ".count", stepCounts, 8);
        appendEngineCauseCounts(out, engineCauseCounts);
        appendStableAwaitTimeoutCounts(out, engineCauseCounts);
        out.put(SUMMARY_PREFIX + "digests", capList(digests, 30));
    }

    private static void appendEmptyEventSummary(Map<String, Object> out) {
        out.put(SUMMARY_PREFIX + "count", 0);
        out.put(SUMMARY_PREFIX + "await_timeout.count", 0L);
        out.put(SUMMARY_PREFIX + "engine.Naver.cause.await_timeout.count", 0L);
        out.put(SUMMARY_PREFIX + "engine.Brave.cause.await_timeout.count", 0L);
        out.put(SUMMARY_PREFIX + "engine.SerpApi.cause.await_timeout.count", 0L);
        out.put(SUMMARY_PREFIX + "engine.Tavily.cause.await_timeout.count", 0L);
    }

    private static void appendSkippedSummary(Map<String, Object> out, Map<String, Object> skippedCtx) {
        long total = 0L;
        for (Map.Entry<String, Object> e : skippedCtx.entrySet()) {
            if (e == null || e.getKey() == null) {
                continue;
            }
            String key = e.getKey();
            if (key.startsWith(SKIPPED_PREFIX) && key.endsWith(".count")) {
                String engine = suffixMiddle(key, ".count");
                if (engine == null) {
                    continue;
                }
                long value = toLong(e.getValue());
                total += value;
                out.put(SUMMARY_PREFIX + "skipped.engine." + sanitizeKeyPart(engine) + ".count", value);
            }
            if (key.startsWith(SKIPPED_PREFIX) && key.endsWith(".last")) {
                String engine = suffixMiddle(key, ".last");
                if (engine == null) {
                    continue;
                }
                out.put(SUMMARY_PREFIX + "skipped.engine." + sanitizeKeyPart(engine) + ".last",
                        SafeRedactor.traceLabel(e.getValue()));
            }
        }

        putRedactedIfPresent(out, skippedCtx, "web.await.skipped.last", SUMMARY_PREFIX + "skipped.last");
        putRedactedIfPresent(out, skippedCtx, "web.await.skipped.last.engine",
                SUMMARY_PREFIX + "skipped.last.engine");
        putRedactedIfPresent(out, skippedCtx, "web.await.skipped.last.reason",
                SUMMARY_PREFIX + "skipped.last.reason");
        putRedactedIfPresent(out, skippedCtx, "web.await.skipped.last.step",
                SUMMARY_PREFIX + "skipped.last.step");
        out.put(SUMMARY_PREFIX + "skipped.total", total);
    }

    private static void appendCountEntries(Map<String, Object> out, String prefix, String suffix,
            Map<String, Integer> counts, int cap) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int max = Math.min(cap, entries.size());
        for (int idx = 0; idx < max; idx++) {
            Map.Entry<String, Integer> e = entries.get(idx);
            if (e == null || e.getKey() == null) {
                continue;
            }
            out.put(prefix + sanitizeKeyPart(e.getKey()) + suffix, e.getValue());
        }
    }

    private static void appendEngineCauseCounts(Map<String, Object> out, Map<String, Integer> engineCauseCounts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(engineCauseCounts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int cap = Math.min(12, entries.size());
        for (int idx = 0; idx < cap; idx++) {
            Map.Entry<String, Integer> e = entries.get(idx);
            if (e == null || e.getKey() == null) {
                continue;
            }
            String[] parts = e.getKey().split("\\|", 2);
            String engine = parts.length > 0 ? parts[0] : "?";
            String cause = parts.length > 1 ? parts[1] : "";
            out.put(SUMMARY_PREFIX + "engine." + sanitizeKeyPart(engine) + ".cause."
                    + sanitizeKeyPart(cause) + ".count", e.getValue());
        }
    }

    private static void appendStableAwaitTimeoutCounts(Map<String, Object> out,
            Map<String, Integer> engineCauseCounts) {
        long naverAwaitTimeout = 0L;
        long braveAwaitTimeout = 0L;
        long serpApiAwaitTimeout = 0L;
        long tavilyAwaitTimeout = 0L;
        for (Map.Entry<String, Integer> e : engineCauseCounts.entrySet()) {
            if (e == null || e.getKey() == null) {
                continue;
            }
            String[] parts = e.getKey().split("\\|", 2);
            if (parts.length < 2) {
                continue;
            }
            String engine = parts[0];
            String cause = parts[1];
            if (!isAwaitTimeout(cause.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            int value = e.getValue() == null ? 0 : e.getValue();
            if ("naver".equalsIgnoreCase(engine)) {
                naverAwaitTimeout += value;
            } else if ("brave".equalsIgnoreCase(engine)) {
                braveAwaitTimeout += value;
            } else if ("serpapi".equalsIgnoreCase(engine) || "serp_api".equalsIgnoreCase(engine)) {
                serpApiAwaitTimeout += value;
            } else if ("tavily".equalsIgnoreCase(engine)) {
                tavilyAwaitTimeout += value;
            }
        }
        out.put(SUMMARY_PREFIX + "engine.Naver.cause.await_timeout.count", naverAwaitTimeout);
        out.put(SUMMARY_PREFIX + "engine.Brave.cause.await_timeout.count", braveAwaitTimeout);
        out.put(SUMMARY_PREFIX + "engine.SerpApi.cause.await_timeout.count", serpApiAwaitTimeout);
        out.put(SUMMARY_PREFIX + "engine.Tavily.cause.await_timeout.count", tavilyAwaitTimeout);
    }

    private static void putRedactedIfPresent(Map<String, Object> out, Map<String, Object> source,
            String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null) {
            out.put(targetKey, SafeRedactor.traceLabel(value));
        }
    }

    @Nullable
    private static String suffixMiddle(String key, String suffix) {
        int start = SKIPPED_PREFIX.length();
        int end = key.length() - suffix.length();
        if (end <= start) {
            return null;
        }
        return key.substring(start, end);
    }

    private static String digest(String engine, String stage, String step, String cause, long waited, long timeoutMs,
            boolean skip, boolean timeout, boolean awaitTimeout, boolean softTimeout, boolean hardTimeout,
            boolean nonOk) {
        StringBuilder sb = new StringBuilder();
        sb.append(engine);
        if (!stage.isBlank()) {
            sb.append(":").append(stage);
        }
        if (!step.isBlank()) {
            sb.append(":").append(step);
        }
        if (!cause.isBlank()) {
            sb.append(":").append(SafeRedactor.traceLabel(cause));
        }
        if (waited > 0L) {
            sb.append(":w=").append(waited);
        }
        if (timeoutMs > 0L) {
            sb.append(":t=").append(timeoutMs);
        }
        if (skip) {
            sb.append(":skip");
        }
        if (timeout) {
            sb.append(":timeout");
        }
        if (awaitTimeout) {
            sb.append(":awaitTimeout");
        }
        if (softTimeout) {
            sb.append(":softTimeout");
        }
        if (hardTimeout) {
            sb.append(":hardTimeout");
        }
        if (nonOk) {
            sb.append(":nonOk");
        }
        return sb.toString();
    }

    private static boolean isAwaitTimeout(String causeLower) {
        return "await_timeout".equals(causeLower)
                || "awaittimeout".equals(causeLower)
                || "await-timeout".equals(causeLower);
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            WebFailSoftTraceSuppressions.trace("webAwaitEventsSummary.toLong", ignore);
            return 0L;
        }
    }

    private static String sanitizeKeyPart(String value) {
        if (value == null) {
            return "null";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "empty";
        }

        StringBuilder b = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            b.append(ok ? c : '_');
            if (b.length() >= 48) {
                break;
            }
        }
        String out = b.toString();
        while (out.contains("__")) {
            out = out.replace("__", "_");
        }
        return out;
    }

    private static String stringOrDefault(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() ? fallback : s;
    }

    private static List<String> capList(List<String> list, int max) {
        if (list == null || list.size() <= max) {
            return list;
        }
        return new ArrayList<>(list.subList(0, max));
    }
}
