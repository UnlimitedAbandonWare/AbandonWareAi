package com.example.lms.trace.attribution;

import com.example.lms.util.HtmlTextUtil;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trace-Ablation Attribution (TAA).
 *
 * <p>
 * A debugging helper that explains degraded outcomes using:
 * <ul>
 * <li><b>Trace-Score</b> (evidence weighted scoring)</li>
 * <li><b>Self-Ask</b> chains (Q/A style explanation nodes)</li>
 * <li><b>Beam Search</b> over candidate hypotheses to find high-scoring
 * explanations</li>
 * <li><b>Ablation Attribution</b> (leave-one-out marginal risk
 * contribution)</li>
 * </ul>
 */
@Component
public class TraceAblationAttributionService {

    private static final Logger log = LoggerFactory.getLogger(TraceAblationAttributionService.class);

    private static final String VERSION = "taa-1.0";

    // Outcome thresholds (heuristic)
    private static final double IRREGULARITY_COMPRESSION_THRESHOLD = 0.25;

    // Beam search params (small by design)
    private static final int DEFAULT_BEAM_WIDTH = 6;
    private static final int DEFAULT_MAX_DEPTH = 6;

    // Evidence patterns
    private static final Pattern P_NOFILTER = Pattern.compile("NOFILTER", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_FALLBACK_BLANK = Pattern.compile("fallback_blank|blank\\s+llm",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_UNVERIFIED = Pattern.compile("unverified|unconfirmed|unknown",
            Pattern.CASE_INSENSITIVE);

    // URL recovery (fail-soft) for evidence diversity
    private static final Pattern P_URL_IN_TEXT = Pattern.compile("(https?://[^\\s\\]\\)]+)", Pattern.CASE_INSENSITIVE);


    public TraceAblationAttributionResult analyze(
            Map<String, Object> trace,
            List<Content> webTopK,
            List<Content> vectorTopK) {
        if (trace == null || trace.isEmpty()) {
            return new TraceAblationAttributionResult(VERSION, "NO_TRACE", 0.0, List.of(), List.of(), Map.of());
        }

        try {
            String outcome = inferOutcome(trace);
            List<CauseNode> candidates = buildCandidates(trace, webTopK, vectorTopK);

            if (candidates.isEmpty()) {
                return new TraceAblationAttributionResult(VERSION, outcome, 0.0, List.of(), List.of(),
                        Map.of("note", "no candidates"));
            }

            List<Path> beams = beamSearch(candidates, DEFAULT_BEAM_WIDTH, DEFAULT_MAX_DEPTH);
            if (beams.isEmpty()) {
                return new TraceAblationAttributionResult(VERSION, outcome, 0.0, List.of(), List.of(),
                        Map.of("note", "no beams"));
            }

            // Convert beams -> user-visible beams + weights
            List<TraceAblationAttributionResult.Beam> outBeams = buildOutputBeams(beams);

            // Attribution across beams (ablation / leave-one-out)
            Map<String, Double> contributionById = computeAttributions(beams);
            double bestRisk = sigmoid(beams.get(0).score);

            List<TraceAblationAttributionResult.Contributor> contributors = beams.get(0).nodes.stream()
                    .map(n -> toContributor(n, contributionById.getOrDefault(n.id, 0.0)))
                    .sorted(Comparator.comparingDouble(TraceAblationAttributionResult.Contributor::contribution)
                            .reversed())
                    .limit(8)
                    .toList();

            Map<String, Object> debug = new LinkedHashMap<>();
            debug.put("outcome", outcome);
            debug.put("candidateCount", candidates.size());
            debug.put("beamCount", outBeams.size());
            debug.put("bestPathScore", round3(beams.get(0).score));

            return new TraceAblationAttributionResult(
                    VERSION,
                    outcome,
                    round3(bestRisk),
                    contributors,
                    outBeams,
                    debug);
        } catch (Exception e) {
            // Debug features must never break the main pipeline.
            log.warn("[taa] analysis failed: {}", e.toString());
            return new TraceAblationAttributionResult(VERSION, "ERROR", 0.0, List.of(), List.of(),
                    Map.of("error", e.toString()));
        }
    }

    // ------------------------------
    // Candidate generation
    // ------------------------------

    private List<CauseNode> buildCandidates(Map<String, Object> trace, List<Content> webTopK,
            List<Content> vectorTopK) {
        List<CauseNode> out = new ArrayList<>();

        // 1) Web: starvation failsoft / policy mismatch
        boolean starvUsed = getBool(trace, "web.failsoft.starvationFallback.used", false);
        boolean officialOnly = getBool(trace, "web.failsoft.officialOnly", false);
        String starvationPath = getString(trace, "web.failsoft.starvationFallback", "");
        String stageCountsSelected = Objects.toString(trace.getOrDefault("web.failsoft.stageCountsSelected", ""), "");
        String stageCountsRaw = Objects.toString(trace.getOrDefault("web.failsoft.stageCountsRaw", ""), "");
        String stageCountsSelectedFromOut = Objects.toString(
                trace.getOrDefault("web.failsoft.stageCountsSelectedFromOut", ""), "");
        int rawInputCount = getInt(trace, "web.failsoft.rawInputCount", -1);

        // Provider strictness checkpoints (Naver strictDomainRequired / domain filter)
        boolean planHintStrict = getBool(trace, "web.naver.planHintStrict", false);
        boolean strictDomainRequired = getBool(trace, "web.naver.strictDomainRequired", false);
        int naverRawCount = getInt(trace, "web.naver.filter.rawCount", -1);
        int naverAfterStrictCount = getInt(trace, "web.naver.filter.afterStrictCount", -1);
        int naverDroppedStrict = getInt(trace, "web.naver.filter.dropped.strictDomain", -1);
        int naverDroppedBlocked = getInt(trace, "web.naver.filter.dropped.blocked", -1);
        boolean naverStarvedByStrict = getBool(trace, "web.naver.filter.starvedByStrictDomain", false);

        String poolUsed = getString(trace, "web.failsoft.starvationFallback.poolUsed", "");
        int poolSafeSize = getInt(trace, "web.failsoft.starvationFallback.pool.safe.size", -1);
        int poolDevSize = getInt(trace, "web.failsoft.starvationFallback.pool.dev.size", -1);

        int starvCount = getInt(trace, "web.failsoft.starvationFallback.count", 0);

        boolean nofilterLeak = P_NOFILTER.matcher(stageCountsSelected).find()
                || P_NOFILTER.matcher(starvationPath).find();

        if (starvUsed || nofilterLeak) {
            double score = 1.25 + 0.18 * Math.log1p(Math.max(1, starvCount));
            List<String> ev = new ArrayList<>();
            ev.add("web.failsoft.starvationFallback.used=" + starvUsed);
            if (!starvationPath.isBlank())
                ev.add("web.failsoft.starvationFallback=" + starvationPath);
            if (officialOnly)
                ev.add("web.failsoft.officialOnly=true");
            if (!stageCountsSelected.isBlank())
                ev.add("web.failsoft.stageCountsSelected=" + compact(stageCountsSelected, 220));
            if (!stageCountsSelectedFromOut.isBlank())
                ev.add("web.failsoft.stageCountsSelectedFromOut=" + compact(stageCountsSelectedFromOut, 220));
            if (!stageCountsRaw.isBlank())
                ev.add("web.failsoft.stageCountsRaw=" + compact(stageCountsRaw, 220));
            if (rawInputCount >= 0)
                ev.add("web.failsoft.rawInputCount=" + rawInputCount);

            if (!poolUsed.isBlank())
                ev.add("web.failsoft.starvationFallback.poolUsed=" + poolUsed);
            if (poolSafeSize >= 0)
                ev.add("web.failsoft.starvationFallback.pool.safe.size=" + poolSafeSize);
            if (poolDevSize >= 0)
                ev.add("web.failsoft.starvationFallback.pool.dev.size=" + poolDevSize);

            if (starvCount > 0)
                ev.add("web.failsoft.starvationFallback.count=" + starvCount);

            // Naver strict-domain attribution: did provider return 0, or did strict filter
            // drop candidates?
            if (strictDomainRequired || planHintStrict || naverRawCount >= 0) {
                ev.add("web.naver.strictDomainRequired=" + strictDomainRequired);
                ev.add("web.naver.planHintStrict=" + planHintStrict);
                if (naverRawCount >= 0)
                    ev.add("web.naver.filter.rawCount=" + naverRawCount);
                if (naverAfterStrictCount >= 0)
                    ev.add("web.naver.filter.afterStrictCount=" + naverAfterStrictCount);
                if (naverDroppedBlocked >= 0)
                    ev.add("web.naver.filter.dropped.blocked=" + naverDroppedBlocked);
                if (naverDroppedStrict >= 0)
                    ev.add("web.naver.filter.dropped.strictDomain=" + naverDroppedStrict);
                if (naverStarvedByStrict)
                    ev.add("web.naver.filter.starvedByStrictDomain=true");
            }

            // If we can see selected pairs, detect UNVERIFIED or overridePath
            List<Map<String, Object>> selectedPairs = getListOfMap(trace.get("web.failsoft.domainStagePairs.selected"));
            if (!selectedPairs.isEmpty()) {
                long unverified = selectedPairs.stream()
                        .map(m -> Objects.toString(m.getOrDefault("cred", ""), ""))
                        .filter(s -> s.equalsIgnoreCase("UNVERIFIED"))
                        .count();
                if (unverified > 0) {
                    score += 0.15;
                    ev.add("web.failsoft.domainStagePairs.selected.cred.UNVERIFIED=" + unverified);
                }
            }

            out.add(new CauseNode(
                    "web.starvation_failsoft",
                    "WEB",
                    "Web 검색이 starvation → failsoft 경로로 떨어짐(정책/필터 과강성 가능)",
                    score,
                    "왜 web이 failsoft로 떨어졌나?",
                    "starvationFallback 사용 및 NOFILTER 계열 stage 흔적이 관측됨",
                    ev,
                    List.of(
                            "officialOnly/strict 정책이 과하게 적용되는 구간(예: planHintStrict) 추적",
                            "strictDomainRequired=true에서 provider rawCount vs strict-domain dropCounts(web.naver.filter.*)로 분리 추적",
                            "starvationFallback 경로에서 cred=UNVERIFIED 비중이 높으면 최소 1개 OFFICIAL/DOCS 강제"),
                    Set.of(
                            "web.failsoft.starvationFallback.used",
                            "web.failsoft.starvationFallback",
                            "web.failsoft.stageCountsSelected",
                            "web.failsoft.domainStagePairs.selected",
                            "web.failsoft.officialOnly",
                            "web.failsoft.starvationFallback.count")));
        }

        // 2) Web: Brave await skipped
        int braveSkipped = getInt(trace, "web.await.skipped.Brave.count", 0);
        if (braveSkipped > 0) {
            double score = 0.65 + 0.22 * Math.log1p(braveSkipped);
            out.add(new CauseNode(
                    "web.await_skipped_brave",
                    "WEB",
                    "Web 검색 동시성(Brave) await가 skip되어 검색 다양성이 감소",
                    score,
                    "왜 Brave 결과가 skip 되었나?",
                    "web.await.skipped.Brave.count=" + braveSkipped,
                    List.of("web.await.skipped.Brave.count=" + braveSkipped),
                    List.of(
                            "web.await.events를 TraceStore에 기록(현재는 skip count만 보임)",
                            "missing_future / timeout / budget_exhausted 등 원인별 분해 로깅"),
                    Set.of("web.await.skipped.Brave.count")));
        }

        // 3) KeywordSelection fallback blank (aux LLM blank)
        boolean keywordBlank = false;
        List<String> rules = getStringList(trace.get("web.selectedTerms.rules"));
        if (!rules.isEmpty()) {
            keywordBlank = rules.stream().anyMatch(r -> P_FALLBACK_BLANK.matcher(r).find());
        }
        if (keywordBlank) {
            double score = 0.95;
            List<String> ev = new ArrayList<>();
            ev.addAll(rules.stream().filter(r -> P_FALLBACK_BLANK.matcher(r).find()).limit(3).toList());
            // If nightmare blank events are present, bump
            List<String> nightmareBlanks = getStringList(trace.get("nightmare.blank.events"));
            if (!nightmareBlanks.isEmpty()) {
                score += 0.20;
                ev.add("nightmare.blank.events=" + compact(String.join(" | ", nightmareBlanks), 220));
            }
            out.add(new CauseNode(
                    "aux.keyword_selection_blank",
                    "AUX",
                    "KeywordSelection LLM이 blank 출력 → fallback 키워드 사용",
                    score,
                    "왜 keywordSelection이 fallback_blank가 되었나?",
                    "rules에 fallback_blank/blank llm 흔적이 있음",
                    ev,
                    List.of(
                            "keyword-selection:select 응답 공백/타임아웃을 TraceStore에 남기기(이벤트/모델/지연)",
                            "NightmareBreaker blank 이벤트를 TAA 근거로 연결",
                            "fallback 키워드 생성 규칙을 query-aware로 개선(단일 토큰/단어 회피)"),
                    Set.of("web.selectedTerms.rules", "nightmare.blank.events")));
        }

        // 4) Aux blocked: queryTransformer blocked due to compression-mode
        List<Map<String, Object>> blocked = getListOfMap(trace.get("aux.blocked.events"));
        if (!blocked.isEmpty()) {
            List<Map<String, Object>> qtx = blocked.stream()
                    .filter(m -> Objects.toString(m.getOrDefault("stage", ""), "").equalsIgnoreCase("queryTransformer"))
                    .toList();
            if (!qtx.isEmpty()) {
                String reason = Objects.toString(qtx.get(0).getOrDefault("reason", ""), "");
                double score = 0.70;
                if (reason.toLowerCase(Locale.ROOT).contains("compression"))
                    score += 0.15;
                out.add(new CauseNode(
                        "aux.blocked.query_transformer",
                        "AUX",
                        "QueryTransformer 단계가 차단됨(예: compression-mode) → 질의 증강/정규화 손실",
                        score,
                        "왜 queryTransformer가 차단되었나?",
                        "aux.blocked.events에 queryTransformer 기록이 있음: " + compact(reason, 120),
                        List.of("aux.blocked.events.queryTransformer.reason=" + compact(reason, 160)),
                        List.of(
                                "compression-mode가 과도하게 켜지는지(원인: irregularity) 추적",
                                "queryTransformer를 완전 OFF 대신 'cheap transform'로 degrade하는 옵션 추가"),
                        Set.of("aux.blocked.events")));
            }
        }

        // 5) Orchestration: compression due to irregularity
        String orchMode = getString(trace, "orch.mode", "");
        double irregularity = getDouble(trace, "orch.irregularity", 0.0);
        String orchReason = getString(trace, "orch.reason", "");
        if ("COMPRESSION".equalsIgnoreCase(orchMode)
                || (irregularity >= IRREGULARITY_COMPRESSION_THRESHOLD
                        && orchReason.toLowerCase(Locale.ROOT).contains("irregular"))) {
            double score = 0.85 + Math.min(0.45, irregularity);

            List<String> ev = new ArrayList<>();
            if (!orchMode.isBlank())
                ev.add("orch.mode=" + orchMode);
            ev.add("orch.irregularity=" + round3(irregularity));
            if (!orchReason.isBlank())
                ev.add("orch.reason=" + compact(orchReason, 160));

            // If faultmask events exist, raise score (often the driver)
            List<String> fmEvents = getStringList(trace.get("faultmask.events"));
            if (!fmEvents.isEmpty()) {
                score += 0.25;
                ev.add("faultmask.events.size=" + fmEvents.size());
                ev.add("faultmask.events.sample="
                        + compact(String.join(" | ", fmEvents.stream().limit(2).toList()), 180));
            }

            out.add(new CauseNode(
                    "orch.irregularity_compression",
                    "ORCH",
                    "Irregularity 상승으로 ORCH가 COMPRESSION 모드로 전환(기능 비활성화 연쇄)",
                    score,
                    "왜 COMPRESSION 모드가 되었나?",
                    "orch.irregularity/ orch.reason에서 irregularity 기반 전환이 관측됨",
                    ev,
                    List.of(
                            "irregularity 상승 이벤트(faultmask, timeout, missing_future)를 trace에 '원인별'로 누적",
                            "compression-mode에서 critical aux만 선택적으로 유지하는 beam policy 도입"),
                    Set.of("orch.mode", "orch.irregularity", "orch.reason", "faultmask.events")));
        }

        // 6) Guard escalations (if traced)
        boolean guardEscalated = getBool(trace, "guard.escalated", false);
        if (guardEscalated) {
            double score = 0.40;
            List<String> ev = new ArrayList<>();
            ev.add("guard.escalated=true");
            addIfPresent(ev, trace, "guard.escalation.reason");
            addIfPresent(ev, trace, "guard.escalation.triggers");
            addIfPresent(ev, trace, "guard.escalation.evidenceQuality");
            addIfPresent(ev, trace, "guard.escalation.coverage");
            addIfPresent(ev, trace, "guard.escalation.model");
            addIfPresent(ev, trace, "guard.escalation.uniqueDomains");
            addIfPresent(ev, trace, "guard.escalation.lowEvidenceDiversity");
            addIfPresent(ev, trace, "guard.escalation.urlBackedCount");

            out.add(new CauseNode(
                    "guard.escalation",
                    "GUARD",
                    "Guard가 증거 부족/약한 초안 패턴으로 상위 모델로 escalation",
                    score,
                    "왜 guard가 escalation 했나?",
                    "TraceStore에 guard.escalated=true가 기록됨",
                    ev,
                    List.of(
                            "escalation 트리거(weakDraft/strongEvidenceIgnored 등)별 빈도 집계",
                            "증거 품질이 낮으면 '답변' 대신 근거 목록으로 degrade하도록 가드 정책 강화"),
                    Set.of("guard.escalated", "guard.escalation.reason", "guard.escalation.triggers",
                            "guard.escalation.evidenceQuality", "guard.escalation.coverage", "guard.escalation.model",
                            "guard.escalation.uniqueDomains", "guard.escalation.lowEvidenceDiversity", "guard.escalation.urlBackedCount")));
        }

        // 7) Vector retrieval low-quality (heuristic on text)
        VectorSignal vectorSignal = inspectVectorQuality(vectorTopK);
        if (vectorSignal.lowQuality) {
            double score = 0.35 + Math.min(0.35, vectorSignal.badRatio);
            out.add(new CauseNode(
                    "vector.low_quality",
                    "VECTOR",
                    "Vector TopK에 unverified/unknown 성격의 chunk가 다수 → 노이즈 가능",
                    score,
                    "왜 vector 결과가 노이즈인가?",
                    "unverified/unknown 키워드 비율이 높음 (ratio=" + round3(vectorSignal.badRatio) + ")",
                    List.of("vector.badRatio=" + round3(vectorSignal.badRatio)),
                    List.of(
                            "Vector ingest 시 verified=false chunk 가중치/필터 정책 재검토",
                            "entity disambiguation 후 vector 검색(동명이인/단어 충돌)"),
                    Set.of("vectorTopK")));
        }

        // 8) Web evidence quality low (heuristic)
        WebSignal webSignal = inspectWebQuality(webTopK);
        if (webSignal.lowEvidenceDiversity) {
            double score = 0.28 + Math.min(0.30, webSignal.lowDiversityRatio);
            out.add(new CauseNode(
                    "web.low_diversity",
                    "WEB",
                    "Web TopK 증거 다양성이 낮음(도메인/문서 타입 편중)",
                    score,
                    "왜 web 증거 다양성이 낮나?",
                    "uniqueDomains=" + webSignal.uniqueDomains + ", topK=" + webSignal.total,
                    List.of("web.uniqueDomains=" + webSignal.uniqueDomains, "web.topK=" + webSignal.total),
                    List.of(
                            "failsoft stage 믹싱 시 도메인 다양성 보장(최소 N개 도메인)",
                            "OFFICIAL/DOCS stage가 starve되면 'official query'를 1회 더 시도"),
                    Set.of("webTopK")));
        }

        // De-duplicate by id (just in case)
        Map<String, CauseNode> dedup = new LinkedHashMap<>();
        for (CauseNode n : out) {
            if (!dedup.containsKey(n.id))
                dedup.put(n.id, n);
        }
        return new ArrayList<>(dedup.values());
    }

    private static String inferOutcome(Map<String, Object> trace) {
        String orchMode = getString(trace, "orch.mode", "");
        if (!orchMode.isBlank())
            return orchMode;
        if (getBool(trace, "web.failsoft.starvationFallback.used", false))
            return "WEB_FAILSOFT";
        if (getBool(trace, "guard.escalated", false))
            return "GUARD_ESCALATION";
        return "UNKNOWN";
    }

    // ------------------------------
    // Beam search + ablation attribution
    // ------------------------------

    private static List<Path> beamSearch(List<CauseNode> candidates, int beamWidth, int maxDepth) {
        if (candidates == null || candidates.isEmpty())
            return List.of();

        // Sort by score desc to encourage good expansions early
        List<CauseNode> sorted = candidates.stream()
                .sorted(Comparator.comparingDouble((CauseNode n) -> n.score).reversed())
                .toList();

        List<Path> beams = new ArrayList<>();
        beams.add(new Path(List.of(), 0.0));

        for (int depth = 0; depth < maxDepth; depth++) {
            List<Path> next = new ArrayList<>();
            for (Path p : beams) {
                for (CauseNode n : sorted) {
                    if (p.contains(n.id))
                        continue;
                    List<CauseNode> newNodes = new ArrayList<>(p.nodes);
                    newNodes.add(n);
                    double newScore = scoreForNodes(newNodes);
                    next.add(new Path(newNodes, newScore));
                }
            }
            if (next.isEmpty())
                break;
            next.sort(Comparator.comparingDouble((Path p) -> p.score).reversed());
            beams = next.stream().limit(beamWidth).toList();
        }

        // Final sort
        beams = beams.stream().sorted(Comparator.comparingDouble((Path p) -> p.score).reversed()).toList();
        return beams;
    }

    private static double scoreForNodes(List<CauseNode> nodes) {
        if (nodes == null || nodes.isEmpty())
            return 0.0;
        double sum = 0.0;
        for (CauseNode n : nodes) {
            sum += n.score;
        }

        // Overlap penalty: discourage redundant explanations sharing many evidence
        // keys.
        double overlapPenalty = 0.0;
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                CauseNode a = nodes.get(i);
                CauseNode b = nodes.get(j);
                double jacc = jaccard(a.evidenceKeys, b.evidenceKeys);
                if (jacc <= 0.0)
                    continue;
                overlapPenalty += 0.25 * jacc * Math.min(a.score, b.score);
            }
        }
        return sum - overlapPenalty;
    }

    private static Map<String, Double> computeAttributions(List<Path> beams) {
        if (beams == null || beams.isEmpty())
            return Map.of();

        // Softmax weights over beam scores
        List<Double> beamWeights = softmax(beams.stream().map(b -> b.score).toList(), 1.0);

        Map<String, Double> contrib = new HashMap<>();

        for (int bi = 0; bi < beams.size(); bi++) {
            Path p = beams.get(bi);
            double w = beamWeights.get(bi);
            double risk = sigmoid(p.score);

            for (CauseNode n : p.nodes) {
                // leave-one-out (ablation): recompute score without this node
                List<CauseNode> without = p.nodes.stream().filter(x -> !x.id.equals(n.id)).toList();
                double scoreWithout = scoreForNodes(without);
                double riskWithout = sigmoid(scoreWithout);
                double marginal = Math.max(0.0, risk - riskWithout);
                contrib.merge(n.id, w * marginal, Double::sum);
            }
        }

        // Normalize
        double total = contrib.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            // Fallback: score-based normalization from best beam
            Path best = beams.get(0);
            double sum = best.nodes.stream().mapToDouble(n -> n.score).sum();
            if (sum <= 0)
                return Map.of();
            Map<String, Double> byId = new HashMap<>();
            for (CauseNode n : best.nodes)
                byId.put(n.id, n.score / sum);
            return byId;
        }

        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> e : contrib.entrySet()) {
            normalized.put(e.getKey(), e.getValue() / total);
        }
        return normalized;
    }

    private static List<TraceAblationAttributionResult.Beam> buildOutputBeams(List<Path> beams) {
        if (beams == null || beams.isEmpty())
            return List.of();

        List<Double> weights = softmax(beams.stream().map(b -> b.score).toList(), 1.0);
        DecimalFormat df = new DecimalFormat("0.000");
        List<TraceAblationAttributionResult.Beam> out = new ArrayList<>();

        for (int i = 0; i < beams.size(); i++) {
            Path p = beams.get(i);
            double w = weights.get(i);
            List<TraceAblationAttributionResult.QaStep> steps = new ArrayList<>();

            for (CauseNode n : p.nodes) {
                steps.add(new TraceAblationAttributionResult.QaStep(
                        n.qaQuestion,
                        n.qaAnswer + " (traceScore=" + df.format(n.score) + ")",
                        round3(n.score),
                        n.evidence));
            }

            out.add(new TraceAblationAttributionResult.Beam(round3(p.score), round3(w), steps));
        }

        return out;
    }

    private static TraceAblationAttributionResult.Contributor toContributor(CauseNode n, double contribution) {
        return new TraceAblationAttributionResult.Contributor(
                n.id,
                n.group,
                n.title,
                round3(contribution),
                round3(n.score),
                n.evidence,
                n.recommendations);
    }

    // ------------------------------
    // Heuristics: vector/web quality
    // ------------------------------

    private static VectorSignal inspectVectorQuality(List<Content> vectorTopK) {
        if (vectorTopK == null || vectorTopK.isEmpty())
            return new VectorSignal(false, 0.0);
        int total = 0;
        int bad = 0;
        for (Content c : vectorTopK) {
            if (c == null || c.textSegment() == null)
                continue;
            String txt = Optional.ofNullable(c.textSegment().text()).orElse("");
            if (txt.isBlank())
                continue;
            total++;
            if (P_UNVERIFIED.matcher(txt).find())
                bad++;
        }
        if (total <= 0)
            return new VectorSignal(false, 0.0);
        double ratio = bad / (double) total;
        return new VectorSignal(ratio >= 0.45 && total >= 3, ratio);
    }

    private static WebSignal inspectWebQuality(List<Content> webTopK) {
        if (webTopK == null || webTopK.isEmpty())
            return new WebSignal(false, 0, 0, 0.0);

        Set<String> domains = new HashSet<>();
        int total = 0;
        int recovered = 0;

        for (Content c : webTopK) {
            if (c == null || c.textSegment() == null)
                continue;

            // 1) Prefer explicit metadata (provenance)
            var md = c.textSegment().metadata();
            String src = "";
            if (md != null) {
                src = md.getString("source");
                if (src == null || src.isBlank()) {
                    src = md.getString("url");
                }
            }

            // 2) Fail-soft: recover URL from text when metadata is missing
            if (src == null || src.isBlank()) {
                String txt = Optional.ofNullable(c.textSegment().text()).orElse("");
                String href = HtmlTextUtil.extractFirstHref(txt);
                if (href != null && !href.isBlank()) {
                    src = HtmlTextUtil.normalizeUrl(href);
                    recovered++;
                } else {
                    var mm = P_URL_IN_TEXT.matcher(txt);
                    if (mm.find()) {
                        src = HtmlTextUtil.normalizeUrl(mm.group(1));
                        recovered++;
                    }
                }
            }

            if (src == null)
                src = "";
            String dom = extractDomain(src);
            if (!dom.isBlank())
                domains.add(dom);
            total++;
        }

        int unique = domains.size();
        double lowDiv = total <= 0 ? 0.0 : Math.max(0.0, (3.0 - unique) / 3.0);
        boolean low = unique <= 1 && total >= 3;

        // Light trace hook (best-effort) for debugging only
        if (recovered > 0) {
            try {
                // not all pipelines attach TraceStore here, so keep it silent
                // (callers can still inspect the returned WebSignal)
            } catch (Throwable ignore) {
            }
        }

        return new WebSignal(low, unique, total, lowDiv);
    }



    private static String extractDomain(String url) {
        if (url == null)
            return "";
        String s = url.trim();
        if (s.isBlank())
            return "";
        try {
            // Very small + safe extraction without URL class (avoids exceptions for non-url
            // ids)
            String lower = s.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf("//");
            if (idx >= 0)
                lower = lower.substring(idx + 2);
            int slash = lower.indexOf('/');
            if (slash >= 0)
                lower = lower.substring(0, slash);
            int q = lower.indexOf('?');
            if (q >= 0)
                lower = lower.substring(0, q);
            int hash = lower.indexOf('#');
            if (hash >= 0)
                lower = lower.substring(0, hash);
            // strip common prefixes
            if (lower.startsWith("www."))
                lower = lower.substring(4);
            // strip port
            int colon = lower.indexOf(':');
            if (colon >= 0)
                lower = lower.substring(0, colon);
            return lower;
        } catch (Exception ignore) {
            return "";
        }
    }

    private record VectorSignal(boolean lowQuality, double badRatio) {
    }

    private record WebSignal(boolean lowEvidenceDiversity, int uniqueDomains, int total, double lowDiversityRatio) {
    }

    // ------------------------------
    // Utilities
    // ------------------------------

    private static boolean getBool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null)
            return def;
        if (v instanceof Boolean b)
            return b;
        String s = Objects.toString(v, "").trim();
        if (s.isBlank())
            return def;
        return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes");
    }

    private static int getInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v == null)
            return def;
        if (v instanceof Number n)
            return n.intValue();
        try {
            return Integer.parseInt(Objects.toString(v, "").trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static double getDouble(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v == null)
            return def;
        if (v instanceof Number n)
            return n.doubleValue();
        try {
            return Double.parseDouble(Objects.toString(v, "").trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static String getString(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null)
            return def;
        String s = Objects.toString(v, "");
        return s.isBlank() ? def : s;
    }

    private static void addIfPresent(List<String> out, Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null)
            return;
        String s = Objects.toString(v, "");
        if (s.isBlank())
            return;
        out.add(key + "=" + compact(s, 200));
    }

    private static String compact(String s, int max) {
        if (s == null)
            return "";
        String x = s.replaceAll("\\s+", " ").trim();
        if (x.length() <= max)
            return x;
        return x.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static double sigmoid(double x) {
        // Numerically stable-ish for typical small x.
        if (x >= 12)
            return 0.999994;
        if (x <= -12)
            return 0.000006;
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty())
            return 0.0;
        int inter = 0;
        for (String x : a) {
            if (b.contains(x))
                inter++;
        }
        int union = a.size() + b.size() - inter;
        if (union <= 0)
            return 0.0;
        return inter / (double) union;
    }

    private static List<Double> softmax(List<Double> xs, double temperature) {
        if (xs == null || xs.isEmpty())
            return List.of();
        double t = temperature <= 0 ? 1.0 : temperature;
        double max = xs.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0.0);
        List<Double> exps = xs.stream()
                .map(x -> x == null ? 0.0 : Math.exp((x - max) / t))
                .toList();
        double sum = exps.stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            double w = 1.0 / exps.size();
            return exps.stream().map(x -> w).toList();
        }
        return exps.stream().map(x -> x / sum).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getListOfMap(Object v) {
        if (v == null)
            return List.of();
        if (v instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> mm = new HashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        mm.put(Objects.toString(e.getKey(), ""), e.getValue());
                    }
                    out.add(mm);
                }
            }
            return out;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Object v) {
        if (v == null)
            return List.of();
        if (v instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(o -> Objects.toString(o, "")).toList();
        }
        if (v instanceof String s) {
            if (s.isBlank())
                return List.of();
            return List.of(s);
        }
        return List.of();
    }

    private static double round3(double x) {
        return Math.round(x * 1000.0) / 1000.0;
    }

    // ------------------------------
    // Internal model
    // ------------------------------

    private static final class CauseNode {
        final String id;
        final String group;
        final String title;
        final double score;

        final String qaQuestion;
        final String qaAnswer;

        final List<String> evidence;
        final List<String> recommendations;
        final Set<String> evidenceKeys;

        private CauseNode(
                String id,
                String group,
                String title,
                double score,
                String qaQuestion,
                String qaAnswer,
                List<String> evidence,
                List<String> recommendations,
                Set<String> evidenceKeys) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.score = score;
            this.qaQuestion = qaQuestion;
            this.qaAnswer = qaAnswer;
            this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
            this.recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
            this.evidenceKeys = evidenceKeys == null ? Set.of() : Set.copyOf(evidenceKeys);
        }
    }

    private static final class Path {
        final List<CauseNode> nodes;
        final double score;

        private Path(List<CauseNode> nodes, double score) {
            this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
            this.score = score;
        }

        boolean contains(String id) {
            if (id == null)
                return false;
            for (CauseNode n : nodes) {
                if (id.equals(n.id))
                    return true;
            }
            return false;
        }
    }
}
