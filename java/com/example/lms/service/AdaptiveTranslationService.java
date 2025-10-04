// ───── src/main/java/com/example/lms/service/AdaptiveTranslationService.java ─────
package com.example.lms.service;

import com.example.lms.client.GTranslateClient;
import com.example.lms.learning.gemini.GeminiClient;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.domain.TranslationSample;
import com.example.lms.domain.enums.RulePhase;
import com.example.lms.domain.enums.TranslationRoute;
import com.example.lms.repository.ConfigRepository;
import com.example.lms.repository.MemoryRepository;
import com.example.lms.repository.SampleRepository;
import com.example.lms.service.QualityMetricService;
import com.example.lms.service.RuleEngine;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.ml.BanditSelector;
import com.example.lms.service.ml.PerformanceMetricService;
import com.example.lms.util.HashUtil;
import com.example.lms.util.TextSimilarityUtil;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.text.DecimalFormat;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 번역 요청 전 과정을 오케스트레이션하는 서비스.
 *
 * <p>책임 분리 원칙을 지켜 계산/튜닝은 하위 서비스(BanditSelector, PerformanceMetricService 등)에
 * 위임하고 자신은 흐름 제어에만 집중한다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@RefreshScope
public class AdaptiveTranslationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdaptiveTranslationService.class);


    /* ──────────────────────────────── 주입 의존성 ──────────────────────────────── */
    private final RuleEngine ruleEngine;
    private final MemoryRepository         memoryRepo;
    private final SampleRepository         sampleRepo;
    private final GTranslateClient         gTranslate;
    private final GeminiClient             gemini;
    private final QualityMetricService qualityMetric;
    private final TextSimilarityUtil       similarityUtil;

    private final BanditSelector           bandit;          // TM 선택 + 볼츠만 결정
    private final PerformanceMetricService metrics;         // 전역 보상 EWMA
    private final HyperparameterService    params;          // DB-기반 하이퍼파라미터
    private final ConfigRepository         configRepo;      // 영속 설정 저장

    /* ──────────────────────────────── 동적 파라미터 ─────────────────────────────── */
    @Value("${translate.bandit.k-top:5}")
    private int topK;

    @Value("${translate.bandit.temperature:0.1}")
    private volatile double boltzmannTemperature;           // 실시간 튜닝 대상

    /* gradient hill-climb 용 LR 및 h */
    @Value("${translate.tuning.learning-rate:0.001}")
    private double temperatureLR;

    @Value("${translate.tuning.h:0.0001}")
    private double h;

    /* 코사인 유사도 Threshold(동적) */
    private volatile double similarityThreshold = 0.85;
    // ===== Temperature tuning (3‑phase central‑difference) =====
    private volatile double tCenter;                   // 기준 온도
    private volatile int     tempTuningPhase = 0;      // 0:probe → 1:-probe → 2:update
    private volatile Double  ewmaPlus = null;
    private volatile Double  ewmaMinus = null;


    /* 포맷터 */
    private static final DecimalFormat DF = new DecimalFormat("#.####");

    /* ──────────────────────────────── 외부 API 회로차단/재시도 ─────────────────── */
    private final CircuitBreaker cb = CircuitBreaker.ofDefaults("gtranslate");
    private final Retry retry       = Retry.of("gtranslate-retry",
            RetryConfig.custom()
                    .maxAttempts(2)
                    .waitDuration(Duration.ofMillis(200))
                    .build());

    /* ═══════════════════ 초기화 – DB persistance ═══════════════════ */
    @PostConstruct
    void loadPersisted() {
        boltzmannTemperature = configRepo.findDouble("boltzmannTemperature")
                .orElse(boltzmannTemperature);
        tCenter = boltzmannTemperature; // 기준값 동기화
        similarityThreshold  = configRepo.findDouble("similarityThreshold")
                .orElse(similarityThreshold);

        log.info("AdaptiveTranslation init → T={}, thr={}",
                DF.format(boltzmannTemperature), DF.format(similarityThreshold));
    }

    /* ═══════════════════ PUBLIC ENTRY ═══════════════════ */
    public Mono<String> translate(String srcText, String srcLang, String tgtLang) {

        /* 1 ─ PRE-RULE */
        String pre = ruleEngine.apply(srcText, srcLang, RulePhase.PRE);
        String hash = HashUtil.sha256(pre);

        /* 2 ─ BanditSelector → TM or Fallback */
        return Mono.fromCallable(() -> findMemoryCandidate(pre, hash))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(opt ->
                        opt.filter(bandit::decideWithBoltzmann)
                                .map(this::useMemory)
                                .orElseGet(() -> fallbackTranslate(pre, srcLang, tgtLang))
                )

                /* 3 ─ POST-RULE + 보상 기록 + 샘플 저장 */
                .map(outcome -> {
                    String post = ruleEngine.apply(outcome.text(), tgtLang, RulePhase.POST);

                    recordReward(outcome.route());
                    saveSample(srcText, post, srcLang, tgtLang, hash, outcome);

                    return post;
                });
    }

    /* ═════════════ PRIVATE HELPERS ═════════════ */

    /* 2-A TM 후보 검색 */
    private Optional<TranslationMemory> findMemoryCandidate(String text, String hash) {

        /* ① 정확 일치 먼저 */
        Optional<TranslationMemory> exact = memoryRepo.findBySourceHash(hash);
        if (exact.isPresent()) return exact;

        /* ② 전체 TM → 유사도 계산 */
        List<TranslationMemory> top = memoryRepo.findAll().stream()
                .peek(tm -> tm.setCosineSimilarity(
                        similarityUtil.calculateSimilarity(
                                text,
                                Optional.ofNullable(tm.getCorrected()).orElse(""))))
                .filter(tm -> tm.getCosineSimilarity() >= similarityThreshold)
                .sorted(Comparator.comparingDouble(TranslationMemory::getCosineSimilarity).reversed())
                .limit(topK)
                .toList();

        if (top.isEmpty()) return Optional.empty();

        /* ③ Soft-max 샘플링 */
        double[] logits = top.stream()
                .mapToDouble(tm -> tm.getCosineSimilarity() / boltzmannTemperature)
                .toArray();
        int idx = softmaxSample(logits);

        return Optional.of(top.get(idx));
    }

    /* 2-B TM 사용 경로 */
    private Mono<TranslationOutcome> useMemory(TranslationMemory tm) {
        log.info("✅ TM 사용(sim={})", DF.format(tm.getCosineSimilarity()));
        return Mono.just(new TranslationOutcome(
                tm.getCorrected(),
                TranslationRoute.MEMORY,
                tm.getCosineSimilarity()
        ));
    }

    /* 2-C Fallback API 경로 */
    private Mono<TranslationOutcome> fallbackTranslate(String text, String src, String tgt) {

        /* Google → Gemini 순으로 시도 */
        return gTranslate.translate(text, src, tgt)
                .transformDeferred(CircuitBreakerOperator.of(cb))
                .transformDeferred(RetryOperator.of(retry))
                .map(t -> new TranslationOutcome(t, TranslationRoute.GT, 0.0))
                .switchIfEmpty(
                        gemini.translate(text, src, tgt)
                                .map(t -> new TranslationOutcome(t, TranslationRoute.GEMINI, 0.0))
                )
                .defaultIfEmpty(new TranslationOutcome(text, TranslationRoute.FAILED, 0.0));
    }

    /* 3-A 보상 기록 */
    private void recordReward(TranslationRoute route) {
        double reward = switch (route) {
            case MEMORY  -> 1.0;
            case GT      -> 0.7;
            case GEMINI  -> 0.6;
            default      -> 0.5; // FAILED 등
        };
        metrics.trackReward(reward);
    }

    /* 3-B 샘플 저장 */
    private void saveSample(String src, String trg,
                            String srcLang, String tgtLang,
                            String hash, TranslationOutcome o) {

        double qErr = qualityMetric.calculateScore(src, trg);

        sampleRepo.save(TranslationSample.builder()
                .sourceText(src)
                .translated(trg)
                .srcLang(srcLang)
                .tgtLang(tgtLang)
                .route(o.route())
                .sourceHash(hash)
                .qError(qErr)
                .similarity(o.similarityScore())     // ← 실제 코사인 유사도 저장
                .build());
    }

    /* ───── soft-max 샘플러 ───── */
    private int softmaxSample(double[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (double l : logits) if (l > max) max = l;

        double sum = 0;
        double[] exp = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            exp[i] = Math.exp(logits[i] - max);
            sum += exp[i];
        }
        double r = ThreadLocalRandom.current().nextDouble() * sum;
        double acc = 0;
        for (int i = 0; i < exp.length; i++) {
            acc += exp[i];
            if (r <= acc) return i;
        }
        return exp.length - 1;
    }

    /* ═════════════ 스케줄러 – Threshold & T 튜닝 ═════════════ */

    /** similarityThreshold ±2 % 랜덤 워크 */
    @Scheduled(fixedRate = 3_600_000)
    public void adjustSimilarityThreshold() {
        double jitter = (ThreadLocalRandom.current().nextDouble() - .5) * 0.04; // ±0.02
        similarityThreshold = Math.max(0.75,
                Math.min(0.95, similarityThreshold + jitter));
        configRepo.save("similarityThreshold", similarityThreshold);
        log.info("🔄 similarityThreshold → {}", DF.format(similarityThreshold));
    }

    /** 실측 EWMA 기반 3‑phase 중앙 차분 T 튜닝 */
    @Scheduled(fixedRate = 7_200_000, initialDelay = 1_800_000)
    public synchronized void tuneTemperature() {
        switch (tempTuningPhase) {
            case 0 -> { // +probe
                double tPlus = clamp(tCenter + h, 0.01, 0.50);
                boltzmannTemperature = tPlus;
                configRepo.save("boltzmannTemperature", boltzmannTemperature);
                tempTuningPhase = 1;
                log.info("🔎 [TUNE‑T] probe+ → T={}", DF.format(boltzmannTemperature));
            }
            case 1 -> { // +측정, -probe
                ewmaPlus = metrics.getRewardEwma(); // EWMA 실측
                double tMinus = clamp(tCenter - h, 0.01, 0.50);
                boltzmannTemperature = tMinus;
                configRepo.save("boltzmannTemperature", boltzmannTemperature);
                tempTuningPhase = 2;
                log.info("🔎 [TUNE‑T] probe‑ → T={}", DF.format(boltzmannTemperature));
            }
            default -> { // 2: -측정, 중앙값 업데이트
                ewmaMinus = metrics.getRewardEwma(); // EWMA 실측
                if (ewmaPlus != null && ewmaMinus != null) {
                    double grad = (ewmaPlus - ewmaMinus) / (2 * h); // 중앙 차분
                    tCenter = clamp(tCenter + temperatureLR * grad, 0.01, 0.50);
                }
                boltzmannTemperature = tCenter;
                configRepo.save("boltzmannTemperature", boltzmannTemperature);
                ewmaPlus = ewmaMinus = null;
                tempTuningPhase = 0;
                log.warn("⚙️ T tuned → {} (center)", DF.format(boltzmannTemperature));
            }
        }
    }

    // 공용 clamp
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /* ═════════════ 내부 Outcome 레코드 ═════════════ */
    private record TranslationOutcome(String text,
                                      TranslationRoute route,
                                      double similarityScore) { }
}
