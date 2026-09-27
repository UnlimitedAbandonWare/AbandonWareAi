package com.example.lms.service.ml;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.MemoryRepository;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.util.TextSimilarityUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * ① 모든 하이퍼파라미터를 {@link HyperparameterService} 로부터 **동적**으로 가져온다.
 * ② TEXT 컬럼과 utf8mb4 설정을 통해 한글/이모지 문제를 해결했다.
 * ③ pick-&-decide 로직은 그대로지만, 파라미터를 언제든 DB에서 바꿀 수 있다.
 */
@Component
@RequiredArgsConstructor
public class BanditSelector {
    private static final Logger log = LoggerFactory.getLogger(BanditSelector.class);

    private final MemoryRepository    memoryRepo;
    private final TextSimilarityUtil  simUtil;
    private final HyperparameterService hp;   // ← 핵심 변경
    @Value("${bandit.temperature.energy:1.0}")
    private double Teng;

    @Value("${bandit.temperature.softmax:1.0}")
    private double Tsoft;

    @Value("${bandit.beta.cosine:1.0}")
    private double betaCos;
    /* 동적으로 흔들리는 유사도 임계값 (jitter 반영) */
    private final AtomicReference<Double> dynThreshold = new AtomicReference<>();

    @PostConstruct
    void init() {
        // 애플리케이션 부팅 시 기본값을 한 번만 읽어 둔다.
        dynThreshold.set(hp.getDouble("bandit.threshold.base", 0.85));
    }

    /* ────────────────────────────── Public API ───────────────────────────── */

    /** ❶ “가장 좋은 TM 후보” 한 개 반환 (없으면 empty) */
    public Optional<TranslationMemory> pickBestMemoryCandidate(String srcText, String srcHash) {

        /* 1) 일치 해시가 있으면 바로 반환 */
        Optional<TranslationMemory> exact = memoryRepo.findBySourceHash(srcHash);
        if (exact.isPresent()) return exact;

        /* 2) top-K 후보 선별 */
        int k            = hp.getInt("memory.k-top", 5);
        double threshold = dynThreshold.get();        // 매번 변할 수 있음

        List<TranslationMemory> topK = memoryRepo.findAll().stream()
                .peek(tm -> tm.setCosineSimilarity(
                        simUtil.calculateSimilarity(srcText, tm.getCorrected())))
                .filter(tm -> tm.getCosineSimilarity() >= threshold)
                .sorted(Comparator.comparingDouble(TranslationMemory::getCosineSimilarity).reversed())
                .limit(k)
                .collect(Collectors.toList());

        if (topK.isEmpty()) return Optional.empty();

        /* 3) 볼츠만(softmax) 샘플링 */
        double T       = hp.getDouble("bandit.temperature", 0.1);
        double sigma   = hp.getDouble("memory.aug-sigma", 0.03);

        // 개선된 로직: (유사도 가중치 + 에너지 텀 + 노이즈) / 온도
        double[] logits = topK.stream().mapToDouble(tm -> {
            double cos = tm.getCosineSimilarity();
            double e   = (tm.getEnergy() == null ? 0.0 : tm.getEnergy());
            // [핵심] 낮은 에너지(e)일수록 점수가 높아지는 항 추가
            double energyTerm = -e / Math.max(1e-6, Teng);
            double noise = ThreadLocalRandom.current().nextGaussian() * sigma;
            return (betaCos * cos + energyTerm + noise) / Math.max(1e-6, Tsoft);
        }).toArray();

        int idx = softmaxSample(logits);
        return Optional.of(topK.get(idx));
    }

    /** ❷ 선택한 TM 을 실제로 “쓸지 말지” 결정하는 볼츠만 정책 */
    public boolean decideWithBoltzmann(TranslationMemory tm) {
        double q      = clip(tm.getQValue());
        double T      = hp.getDouble("bandit.temperature", 0.1);
        double prob   = 1.0 / (1.0 + Math.exp(-q / T));
        boolean useIt = ThreadLocalRandom.current().nextDouble() < prob;

        log.debug("Boltzmann   q'={}.4f  T={}  P={} → {}",
                q, T, prob, useIt ? "USE" : "SKIP");
        return useIt;
    }

    /** ❸ 스케줄러(@Scheduled) 등에서 주기적으로 호출해 유사도 임계값을 살짝 흔든다. */
    public void updateDynamicThreshold() {
        double base   = hp.getDouble("bandit.threshold.base",   0.85);
        double jitter = hp.getDouble("bandit.threshold.jitter", 0.02);
        double newVal = base + (ThreadLocalRandom.current().nextDouble() - .5) * jitter;
        dynThreshold.set(newVal);
        log.info("🔄 유사도 threshold 갱신: {}", String.format("%.4f", newVal));
    }

    /* ───────────────────────── Internal utils ───────────────────────────── */

    private int softmaxSample(double[] logits) {
        double max   = Arrays.stream(logits).max().orElse(0);
        double[] exp = Arrays.stream(logits).map(x -> Math.exp(x - max)).toArray();
        double sum   = Arrays.stream(exp).sum();
        double r     = ThreadLocalRandom.current().nextDouble() * sum;
        double acc   = 0;
        for (int i = 0; i < exp.length; i++) {
            acc += exp[i];
            if (r <= acc) return i;
        }
        return exp.length - 1;
    }

    private double clip(double v) {
        double min = hp.getDouble("clip.min", 0.05);
        double max = hp.getDouble("clip.max", 0.95);
        return Math.max(min, Math.min(max, v));
    }
}