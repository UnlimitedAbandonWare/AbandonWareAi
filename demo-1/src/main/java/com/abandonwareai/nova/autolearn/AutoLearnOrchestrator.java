package com.abandonwareai.nova.autolearn;

import com.abandonwareai.nova.gates.CitationGate;
import com.abandonwareai.nova.gates.FinalSigmoidGate;
import com.abandonwareai.nova.sidetrain.SidetrainAuditor;
import com.abandonwareai.nova.sidetrain.DetourSynthesizer;
import com.abandonwareai.nova.sidetrain.NeedleProbeExecutor;
import com.abandonwareai.nova.sidetrain.SidetrainGate;
import com.abandonwareai.nova.cfvm.CfvmRawService;
import com.abandonwareai.nova.router.LocalLlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AutoLearnOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AutoLearnOrchestrator.class);

    private final LocalLlmService llm;
    private final CitationGate citationGate;
    private final FinalSigmoidGate sigmoidGate;
    private final SidetrainAuditor auditor;
    private final SidetrainGate sidetrainGate;
    private final DetourSynthesizer detourSynthesizer;
    private final NeedleProbeExecutor needleProbe;
    private final DatasetWriter datasetWriter;
    private final CfvmRawService cfvm;
    private final IdleDetector idleDetector;

    private final String planRotate; // simple round-robin plan id list
    private int planIdx = 0;

    public AutoLearnOrchestrator(LocalLlmService llm,
                                 CitationGate citationGate,
                                 FinalSigmoidGate sigmoidGate,
                                 SidetrainAuditor auditor,
                                 SidetrainGate sidetrainGate,
                                 DetourSynthesizer detourSynthesizer,
                                 NeedleProbeExecutor needleProbe,
                                 DatasetWriter datasetWriter,
                                 CfvmRawService cfvm,
                                 IdleDetector idleDetector,
                                 @Value("${idle.plan.rotate:brave,hypernova,zero_break,safe_autorun}") String planRotate) {
        this.llm = llm;
        this.citationGate = citationGate;
        this.sigmoidGate = sigmoidGate;
        this.auditor = auditor;
        this.sidetrainGate = sidetrainGate;
        this.detourSynthesizer = detourSynthesizer;
        this.needleProbe = needleProbe;
        this.datasetWriter = datasetWriter;
        this.cfvm = cfvm;
        this.idleDetector = idleDetector;
        this.planRotate = planRotate;
    }

    public AutoLearnCycleResult runIdleCycle(String sessionId, PreemptionToken token) {
        int attempted = 0;
        int accepted = 0;

        if (shouldAbort(token)) {
            return new AutoLearnCycleResult(0, 0, true);
        }

        String plan = selectAutoLearnPlan();
        Optional<String> q = nextQuestion();
        if (q.isEmpty()) {
            log.info("No available questions for AutoLearn.");
            return new AutoLearnCycleResult(0, 0, false);
        }

        String question = q.get();
        attempted++;

        // PLAN EXECUTION (local only by design)
        if (shouldAbort(token)) return new AutoLearnCycleResult(attempted, 0, true);
        String answer = llm.answer(question, plan, sessionId);

        // Gates
        if (!citationGate.passes(answer)) {
            cfvm.logFailure(sessionId, question, plan, "INSUFFICIENT_EVIDENCE");
            return new AutoLearnCycleResult(attempted, 0, false);
        }
        double score = sigmoidGate.score(answer);
        if (score < sigmoidGate.getMinScore()) {
            cfvm.logFailure(sessionId, question, plan, "LOW_CONFIDENCE(" + String.format("%.2f", score) + ")");
            return new AutoLearnCycleResult(attempted, 0, false);
        }

        // Sidetrain checks (step-wise preemption)
        if (shouldAbort(token)) return new AutoLearnCycleResult(attempted, 0, true);
        String detourQ = detourSynthesizer.rephrase(question);
        if (shouldAbort(token)) return new AutoLearnCycleResult(attempted, 0, true);
        String detourA = llm.answer(detourQ, plan, sessionId);

        if (shouldAbort(token)) return new AutoLearnCycleResult(attempted, 0, true);
        String needleQ = needleProbe.keyFactProbe(question, answer);
        if (shouldAbort(token)) return new AutoLearnCycleResult(attempted, 0, true);
        String needleA = llm.answer(needleQ, plan, sessionId);

        boolean consistent = sidetrainGate.passes(answer, detourA, needleA);
        if (!consistent) {
            cfvm.logFailure(sessionId, question, plan, "SIDETRAIN_INCONSISTENT");
            return new AutoLearnCycleResult(attempted, 0, false);
        }

        if (shouldAbort(token)) return new AutoLearnCycleResult(attempted, 0, true);

        // Persist success
        boolean ok = datasetWriter.appendRecord(sessionId, plan, question, answer);
        if (ok) {
            accepted++;
            log.info("AutoLearn success: {}", question);
        }

        return new AutoLearnCycleResult(attempted, accepted, false);
    }

    private boolean shouldAbort(PreemptionToken token) {
        if (token != null && token.shouldAbort()) return true;
        return idleDetector != null && !idleDetector.isIdle();
    }

    String selectAutoLearnPlan() {
        String[] ids = planRotate.split(",");
        String chosen = ids[planIdx % ids.length].trim();
        planIdx++;
        return chosen;
    }

    Optional<String> nextQuestion() {
        try {
            ClassPathResource res = new ClassPathResource("soak_test_questions.txt");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                List<String> all = br.lines().filter(s -> s != null && !s.isBlank()).collect(Collectors.toList());
                if (all.isEmpty()) return Optional.empty();
                Collections.shuffle(all);
                return Optional.of(all.get(0));
            }
        } catch (IOException e) {
            return Optional.of("What is the purpose of the Idle AutoLearn loop?");
        }
    }
}
