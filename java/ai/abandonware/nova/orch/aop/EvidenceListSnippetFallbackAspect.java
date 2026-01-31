package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Fills missing title/snippet fields when degrading to an evidence list.
 *
 * <p>
 * Problem:
 * - Some providers return URL-only results (blank title/snippet). When the guard
 *   degrades to {@code DEGRADE_EVIDENCE_LIST}, the user-facing output becomes
 *   unreadable.
 *
 * <p>
 * Risk note:
 * - Any fallback derived from URL structure is NOT a true summary.
 *   We therefore generate a clearly-labeled, link-structured snippet.
 *
 * <p>
 * Observability:
 * - guard.degradedToEvidence.titleFallback.count
 * - guard.degradedToEvidence.snippetFallback.count
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EvidenceListSnippetFallbackAspect {

    private final Environment env;

    public EvidenceListSnippetFallbackAspect(Environment env) {
        this.env = env;
    }

    @Around("execution(* com.example.lms.service.guard.EvidenceAwareGuard.degradeToEvidenceList(..))")
    public Object aroundDegradeToEvidenceList(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled()) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();
        if (args == null || args.length == 0 || !(args[0] instanceof List<?> raw)) {
            return pjp.proceed();
        }

        int titleFallbackCount = 0;
        int snippetFallbackCount = 0;
        boolean changed = false;

        String label = snippetLabel();

        List<EvidenceAwareGuard.EvidenceDoc> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (!(o instanceof EvidenceAwareGuard.EvidenceDoc d)) {
                // Unknown element type → do not attempt to rewrite.
                return pjp.proceed();
            }

            String id = clean(d.id());
            String title = clean(d.title());
            String snippet = clean(d.snippet());

            String host = safeHost(id);
            String tail = safePathTail(id);

            boolean docChanged = false;

            String newTitle = title;
            if (!isMeaningfulTitle(title)) {
                String derived = deriveTitleFallback(host, tail, id);
                if (!derived.isBlank()) {
                    newTitle = derived;
                    titleFallbackCount++;
                    docChanged = true;
                }
            }

            String newSnippet = snippet;
            if (newSnippet.isBlank()) {
                String derived = deriveSnippetFallback(label, newTitle, host, tail);
                if (!derived.isBlank()) {
                    newSnippet = derived;
                    snippetFallbackCount++;
                    docChanged = true;
                }
            }

            if (docChanged) {
                changed = true;
                out.add(new EvidenceAwareGuard.EvidenceDoc(id, newTitle, newSnippet));
            } else {
                out.add(d);
            }
        }

        // Always emit counts (even when 0) for observability.
        try {
            TraceStore.put("guard.degradedToEvidence.titleFallback.count", titleFallbackCount);
            TraceStore.put("guard.degradedToEvidence.snippetFallback.count", snippetFallbackCount);
        } catch (Throwable ignore) {
        }

        if (!changed) {
            return pjp.proceed();
        }

        Object[] newArgs = args.clone();
        newArgs[0] = out;
        return pjp.proceed(newArgs);
    }



    /**
     * EvidenceAwareGuard.ensureCoverage() 내부에서 degradeToEvidenceList()가 self-invocation으로 호출되어
     * AOP가 적용되지 않는 경우가 많습니다.

     *

     * 따라서 ensureCoverage() 진입 시점에서 EvidenceDoc의 title/snippet을 보강해,

     * URL-only 근거가 그대로 출력되는 UX를 완화합니다.

     */
    // NOTE: Spring AOP can throw "Required to bind N arguments, but only bound M" if args(...) variables
    //       are not bound reliably (JoinPointMatch NOT bound). To avoid blank chat responses,
    //       do NOT rely on args-binding here. Use ProceedingJoinPoint.getArgs() instead.
    @Around("execution(public com.example.lms.service.guard.EvidenceAwareGuard$Result com.example.lms.service.guard.EvidenceAwareGuard.ensureCoverage(..))")
    public Object aroundEnsureCoverage(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled()) {
            return pjp.proceed();
        }

        try {
            Object[] args = pjp.getArgs();
            if (args == null || args.length < 5) {
                return pjp.proceed();
            }

            Object rawTopDocs = args[1];
            if (!(rawTopDocs instanceof List<?> rawList)) {
                return pjp.proceed();
            }

            // Defensive cast: if element types are not EvidenceDoc, do not attempt to rewrite.
            for (Object o : rawList) {
                if (o != null && !(o instanceof EvidenceAwareGuard.EvidenceDoc)) {
                    return pjp.proceed();
                }
            }

            @SuppressWarnings("unchecked")
            List<EvidenceAwareGuard.EvidenceDoc> topDocs = (List<EvidenceAwareGuard.EvidenceDoc>) (List<?>) rawList;

            String label = snippetLabel();
            Counts counts = new Counts();
            List<EvidenceAwareGuard.EvidenceDoc> out = rewriteDocs(topDocs, label, counts);

            // Always emit counts (even when 0) for observability.
            try {
                TraceStore.put("guard.degradedToEvidence.titleFallback.count", counts.titleFallback);
                TraceStore.put("guard.degradedToEvidence.snippetFallback.count", counts.snippetFallback);
                TraceStore.put("guard.degradedToEvidence.snippetFallback.ensureCoverage.changed", counts.changed);
            } catch (Throwable ignore) {
            }

            if (!counts.changed) {
                return pjp.proceed();
            }

            Object[] newArgs = args.clone();
            newArgs[1] = out;
            return pjp.proceed(newArgs);
        } catch (Throwable t) {
            // Fail-soft: never let AOP issues blank out the chat response.
            try {
                TraceStore.put("guard.degradedToEvidence.snippetFallback.ensureCoverage.error", t.getClass().getSimpleName());
            } catch (Throwable ignore) {
            }
            return pjp.proceed();
        }
    }

    /**
     * EvidenceAnswerComposer 기반 fallback(e.g., LLM_FAST_BAIL_TIMEOUT)에서도
     * URL-only 근거가 그대로 노출되지 않도록 title/snippet을 보강한다.
     */
    @Around("execution(public String com.example.lms.service.rag.EvidenceAnswerComposer.compose(..))")
    public Object aroundEvidenceAnswerComposer(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled()) {
            return pjp.proceed();
        }

        try {
            Object[] args = pjp.getArgs();
            if (args == null || args.length < 3) {
                return pjp.proceed();
            }

            Object rawEvidence = args[1];
            if (!(rawEvidence instanceof List<?> rawList)) {
                return pjp.proceed();
            }

            // Defensive cast: if element types are not EvidenceDoc, do not attempt to rewrite.
            for (Object o : rawList) {
                if (o != null && !(o instanceof EvidenceAwareGuard.EvidenceDoc)) {
                    return pjp.proceed();
                }
            }

            @SuppressWarnings("unchecked")
            List<EvidenceAwareGuard.EvidenceDoc> evidence = (List<EvidenceAwareGuard.EvidenceDoc>) (List<?>) rawList;

            String label = snippetLabel();
            Counts counts = new Counts();

            List<EvidenceAwareGuard.EvidenceDoc> out = rewriteDocs(evidence, label, counts);

            // Always emit counts (even when 0) for observability.
            try {
                TraceStore.put("guard.degradedToEvidence.titleFallback.count", counts.titleFallback);
                TraceStore.put("guard.degradedToEvidence.snippetFallback.count", counts.snippetFallback);
            } catch (Throwable ignore) {
            }

            if (!counts.changed) {
                return pjp.proceed();
            }

            Object[] newArgs = args.clone();
            newArgs[1] = out;
            return pjp.proceed(newArgs);
        } catch (Throwable t) {
            // Fail-soft: never let AOP issues blank out the chat response.
            try {
                TraceStore.put("guard.degradedToEvidence.snippetFallback.evidenceAnswerComposer.error", t.getClass().getSimpleName());
            } catch (Throwable ignore) {
            }
            return pjp.proceed();
        }
    }


    private static final class Counts {
        int titleFallback;
        int snippetFallback;
        boolean changed;
    }

    private List<EvidenceAwareGuard.EvidenceDoc> rewriteDocs(List<EvidenceAwareGuard.EvidenceDoc> raw,
                                                            String label,
                                                            Counts counts) {
        if (raw == null || raw.isEmpty()) {
            return (raw == null) ? Collections.emptyList() : raw;
        }

        List<EvidenceAwareGuard.EvidenceDoc> out = new ArrayList<>(raw.size());
        boolean changed = false;

        for (EvidenceAwareGuard.EvidenceDoc d : raw) {
            if (d == null) {
                out.add(null);
                continue;
            }

            String id = clean(d.id());
            String title = clean(d.title());
            String snippet = clean(d.snippet());

            String host = safeHost(id);
            String tail = safePathTail(id);

            boolean docChanged = false;

            String newTitle = title;
            if (!isMeaningfulTitle(title)) {
                String derived = deriveTitleFallback(host, tail, id);
                if (!derived.isBlank()) {
                    newTitle = derived;
                    counts.titleFallback++;
                    docChanged = true;
                }
            }

            String newSnippet = snippet;
            if (newSnippet.isBlank()) {
                String derived = deriveSnippetFallback(label, newTitle, host, tail);
                if (!derived.isBlank()) {
                    newSnippet = derived;
                    counts.snippetFallback++;
                    docChanged = true;
                }
            }

            if (docChanged) {
                changed = true;
                out.add(new EvidenceAwareGuard.EvidenceDoc(id, newTitle, newSnippet));
            } else {
                out.add(d);
            }
        }

        counts.changed |= changed;
        return changed ? out : raw;
    }

    private boolean enabled() {
        return env.getProperty("nova.orch.evidence-list.snippet-fallback.enabled", Boolean.class, true);
    }

    /**
     * Label shown to users to avoid confusing the derived snippet with an actual summary.
     */
    private String snippetLabel() {
        boolean annotate = env.getProperty("nova.orch.evidence-list.snippet-fallback.annotate", Boolean.class, true);
        if (!annotate) {
            return "";
        }
        String prefix = env.getProperty("nova.orch.evidence-list.snippet-fallback.prefix", String.class, "URL 기반 파생");
        String p = (prefix == null) ? "" : prefix.trim();
        return p.isBlank() ? "URL 기반 파생" : p;
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.isBlank() ? "" : t;
    }

    private static boolean isMeaningfulTitle(String t) {
        if (t == null) {
            return false;
        }
        String s = t.trim();
        if (s.isBlank()) {
            return false;
        }
        String l = s.toLowerCase(Locale.ROOT);
        if (l.equals("제목 없음") || l.equals("[제목 없음]") || l.equals("untitled") || l.equals("(untitled)")) {
            return false;
        }
        // Titles that are literally URLs don't help.
        if (looksLikeUrl(s)) {
            return false;
        }
        return s.length() >= 2;
    }

    private static boolean looksLikeUrl(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://") || l.contains("://");
    }

    private static String deriveTitleFallback(String host, String tail, String id) {
        String h = clean(host);
        String t = clean(tail);

        String title;
        if (!t.isBlank() && !h.isBlank()) {
            title = t + " - " + h;
        } else if (!t.isBlank()) {
            title = t;
        } else if (!h.isBlank()) {
            title = h;
        } else {
            title = id;
        }

        return clip(title, 180);
    }

    private static String deriveSnippetFallback(String label, String title, String host, String tail) {
        String l = clean(label);
        String h = clean(host);
        String t = clean(tail);
        String ti = clean(title);

        // "URL 기반 파생" 등 라벨이 있으면, 사용자에게 '요약'이 아니라는 힌트를 준다.
        if (!l.isBlank()) {
            if (!h.isBlank() && !t.isBlank()) {
                return clip(l + " · " + h + " · " + t, 240);
            }
            if (!h.isBlank() && !ti.isBlank()) {
                return clip(l + " · " + h + " · " + clip(ti, 140), 240);
            }
            if (!h.isBlank()) {
                return clip(l + " · " + h, 240);
            }
            if (!ti.isBlank()) {
                return clip(l + " · " + clip(ti, 140), 240);
            }
            return "";
        }

        // annotate=false → 최소 구조 정보만 제공
        if (!h.isBlank() && !t.isBlank()) {
            return clip(h + " / " + t, 240);
        }
        if (!h.isBlank() && !ti.isBlank()) {
            return clip(h + " / " + clip(ti, 140), 240);
        }
        if (!h.isBlank()) {
            return clip(h, 240);
        }
        return "";
    }

    private static String safeHost(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI u = URI.create(url.trim());
            String host = u.getHost();
            if (host == null) {
                return "";
            }
            String h = host.trim();
            if (h.startsWith("www.")) {
                h = h.substring(4);
            }
            return h;
        } catch (Exception ignore) {
            return "";
        }
    }

    private static String safePathTail(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI u = URI.create(url.trim());
            String path = u.getPath();
            if (path == null || path.isBlank()) {
                return "";
            }
            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String p = parts[i];
                if (p == null) {
                    continue;
                }
                String t = p.trim();
                if (t.isBlank()) {
                    continue;
                }
                // URL decode best-effort.
                try {
                    t = URLDecoder.decode(t, StandardCharsets.UTF_8);
                } catch (Exception ignore) {
                }

                t = t.replace('-', ' ').replace('_', ' ').trim();

                // Drop very common tails.
                String l = t.toLowerCase(Locale.ROOT);
                if (l.equals("index") || l.equals("index.html") || l.equals("index.htm") || l.equals("home") || l.equals("main")) {
                    continue;
                }

                // Strip common extensions.
                t = t.replaceAll("(?i)\\.(html|htm|php|aspx|jsp)$", "").trim();
                if (t.isBlank()) {
                    continue;
                }

                return clip(t, 80);
            }
            return "";
        } catch (Exception ignore) {
            return "";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, Math.max(0, max)).trim();
    }
}
