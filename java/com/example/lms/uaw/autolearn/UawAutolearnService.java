package com.example.lms.uaw.autolearn;

import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.agent.CuriosityTriggerService;
import com.example.lms.agent.KnowledgeGapLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Minimal UAW autolearn "dataset accumulator".
 *
 * <p>
 * P2 (log-based sampling / plan DSL execution) can be layered on top later.
 */
@Service
public class UawAutolearnService {

    private static final Logger log = LoggerFactory.getLogger(UawAutolearnService.class);

    private final ChatService chatService;
    private final UawAutolearnProperties props;
    private final UawSeedSampler seedSampler;
    private final UawDatasetWriter datasetWriter;

    private final KnowledgeGapLogger gapLogger;
    private final ObjectProvider<CuriosityTriggerService> curiosity;

    public UawAutolearnService(ChatService chatService,
            UawAutolearnProperties props,
            UawSeedSampler seedSampler,
            UawDatasetWriter datasetWriter,
            KnowledgeGapLogger gapLogger,
            ObjectProvider<CuriosityTriggerService> curiosity) {
        this.chatService = chatService;
        this.props = props;
        this.seedSampler = seedSampler;
        this.datasetWriter = datasetWriter;
        this.gapLogger = gapLogger;
        this.curiosity = curiosity;
    }

    public AutoLearnCycleResult runCycle(File datasetFile,
            String sessionId,
            PreemptionToken token,
            long deadlineNanos) {
        int attempted = 0;
        int accepted = 0;
        int zeroEvidenceAccepted = 0;
        boolean aborted = false;

        List<String> seeds = buildSeeds();
        int n = Math.min(Math.max(0, props.getBatchSize()), seeds.size());

        for (int i = 0; i < n; i++) {
            if (System.nanoTime() > deadlineNanos)
                break;
            if (token != null && token.shouldAbort()) {
                aborted = true;
                break;
            }

            String q = seeds.get(i);
            attempted++;

            ChatResult r;
            try {
                r = chatService.ask(q);
            } catch (Exception e) {
                log.debug("[UAW] ChatService.ask failed: {}", e.toString());
                continue;
            }

            // [PATCH] Defensive: ChatService may (rarely) return null/blank; avoid NPE and
            // noisy samples.
            if (r == null || r.content() == null || r.content().isBlank()) {
                log.warn("[UAW] ask returned empty result; skip. q={}", shortQ(q));
                continue;
            }

            if (token != null && token.shouldAbort()) {
                aborted = true;
                break;
            }

            int evidenceCount = (r.evidence() == null) ? 0 : r.evidence().size();
            if (evidenceCount < props.getMinEvidenceCount()) {
                // Safety pin: avoid "always skip" deadlocks when evidence pipeline is down.
                // We only allow a very small number of zero-evidence samples per cycle,
                // and only for static internal seeds (NOT gap/curiosity/user-derived prompts).
                boolean allowZero = (evidenceCount == 0)
                        && props.getSafetyPin().isAllowZeroEvidenceForStaticSeeds()
                        && (zeroEvidenceAccepted < Math.max(0,
                                props.getSafetyPin().getMaxZeroEvidenceAcceptedPerCycle()))
                        && isStaticInternalSeed(q);
                if (!allowZero) {
                    log.debug("[UAW] skip sample: insufficient evidence={} q='{}'", evidenceCount, shortQ(q));
                    continue;
                }

                zeroEvidenceAccepted++;
                log.info("[UAW] SAFETY_PIN: accept zero-evidence sample (cycleCap={}): q='{}'",
                        props.getSafetyPin().getMaxZeroEvidenceAcceptedPerCycle(), shortQ(q));
            }

            boolean ok = datasetWriter.append(
                    datasetFile,
                    props.getDataset().getName(),
                    q,
                    r.content(),
                    r.modelUsed(),
                    evidenceCount,
                    sessionId);
            if (ok) {
                accepted++;
            }
        }

        return new AutoLearnCycleResult(attempted, accepted, aborted,
                datasetFile == null ? null : datasetFile.getPath());
    }

    private List<String> buildSeeds() {
        int desired = Math.max(0, props.getBatchSize());
        LinkedHashSet<String> out = new LinkedHashSet<>();

        // 1) Consume recent "gap" events first (best-effort).
        try {
            int gapTake = Math.min(3, Math.max(1, desired));
            for (int i = 0; i < gapTake; i++) {
                if (gapLogger == null)
                    break;
                var evt = gapLogger.poll();
                if (evt == null || evt.isEmpty())
                    break;
                String q = evt.get().getQuery();
                if (q != null && !q.isBlank()) {
                    out.add("내부 자동학습: (gap) " + q.trim());
                }
            }
        } catch (Exception ignore) {
        }

        // 2) Curiosity-based knowledge gap (optional).
        try {
            CuriosityTriggerService svc = (curiosity == null) ? null : curiosity.getIfAvailable();
            if (svc != null) {
                svc.findKnowledgeGap().ifPresent(gap -> {
                    String q = gap.initialQuery();
                    if (q != null && !q.isBlank()) {
                        out.add("내부 자동학습: (curiosity) " + q.trim());
                    }
                });
            }
        } catch (Exception ignore) {
        }

        // 3) Prefer configured seeds, otherwise built-in defaults.
        List<String> baseSeeds = props.getDefaultSeeds();
        if (baseSeeds == null || baseSeeds.isEmpty()) {
            baseSeeds = defaultSeeds();
        }

        // 4) Sample seeds from real user chat history if enabled (also can fill with
        // baseSeeds depending on cfg).
        try {
            if (seedSampler != null) {
                out.addAll(seedSampler.sampleSeeds(props, desired, baseSeeds));
            }
        } catch (Exception ignore) {
            // fall through
        }

        // 5) Final fallback: static seeds if allowed.
        boolean allowStatic = true;
        try {
            if (props.getSeed() != null) {
                allowStatic = props.getSeed().isAllowStaticFallback();
            }
        } catch (Exception ignore) {
        }
        if (allowStatic) {
            out.addAll(baseSeeds);
        }

        return new ArrayList<>(out);
    }

    private static List<String> defaultSeeds() {
        return Arrays.asList(
                "내부 자동학습: 이 시스템의 RAG 파이프라인을 한 문단으로 요약해줘.",
                "내부 자동학습: citation(근거)이 왜 중요한지 설명해줘.",
                "내부 자동학습: 검색 결과가 부족할 때 어떤 안전장치를 써야 하나?");
    }

    private static String shortQ(String q) {
        if (q == null)
            return "";
        return q.length() <= 60 ? q : q.substring(0, 57) + "...";
    }

    /**
     * 쿼리가 시스템 기본 시드인지 확인합니다.
     * gap/curiosity/사용자 유래 프롬프트가 아닌 정적 내부 시드만 true를 반환합니다.
     */
    private static boolean isStaticInternalSeed(String q) {
        if (q == null || q.isBlank())
            return false;
        // defaultSeeds()에 정의된 정적 시드는 "내부 자동학습:" 접두사를 가짐
        // gap/curiosity는 "(gap)" 또는 "(curiosity)" 태깅이 있으므로 제외
        if (!q.startsWith("내부 자동학습:"))
            return false;
        return !q.contains("(gap)") && !q.contains("(curiosity)");
    }
}
