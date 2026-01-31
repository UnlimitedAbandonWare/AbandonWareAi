
package com.example.lms.autolearn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.example.lms.config.AutoLearnProperties;
import com.example.lms.context.AutoLearnContext;
import com.example.lms.dataset.DatasetWriter;
import com.example.lms.cfvm.CfvmRawService;
import com.example.lms.guard.CitationGate;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.sidetrain.DetourSynthesizer;
import com.example.lms.sidetrain.NeedleProbeExecutor;
import com.example.lms.sidetrain.SidetrainAuditor;
import com.example.lms.sidetrain.SidetrainGateImpl;
import com.nova.protocol.plan.PlanApplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

@Service
@ConditionalOnProperty(prefix = "autolearn", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AutolearnService {
    private static final Logger log = LoggerFactory.getLogger(AutolearnService.class);

    private final AutoLearnProperties props;
    private final AutoLearnContext ctx;
    private final PlanApplier planApplier; // optional
    private final CitationGate citationGate;
    private final FinalSigmoidGate finalSigmoidGate;
    private final DetourSynthesizer detourSynthesizer;
    private final NeedleProbeExecutor needleProbeExecutor;
    private final SidetrainAuditor sidetrainAuditor;
    private final SidetrainGateImpl sidetrainGate;
    private final DatasetWriter datasetWriter;
    private final CfvmRawService cfvm;

    private final Deque<String> recentFailures = new ArrayDeque<>();

    public AutolearnService(AutoLearnProperties props,
                            AutoLearnContext ctx,
                            PlanApplier planApplier,
                            CitationGate citationGate,
                            FinalSigmoidGate finalSigmoidGate,
                            DetourSynthesizer detourSynthesizer,
                            NeedleProbeExecutor needleProbeExecutor,
                            SidetrainAuditor sidetrainAuditor,
                            SidetrainGateImpl sidetrainGate,
                            DatasetWriter datasetWriter,
                            CfvmRawService cfvm) {
        this.props = props; this.ctx = ctx; this.planApplier = planApplier;
        this.citationGate = citationGate; this.finalSigmoidGate = finalSigmoidGate;
        this.detourSynthesizer = detourSynthesizer; this.needleProbeExecutor = needleProbeExecutor;
        this.sidetrainAuditor = sidetrainAuditor; this.sidetrainGate = sidetrainGate;
        this.datasetWriter = datasetWriter; this.cfvm = cfvm;
    }

    public enum Plan { BRAVE, HYPER_NOVA, ZERO_BREAK, SIDETRAIN }

    public Plan selectAutoLearnPlan(){
        // Simple heuristic: if last failure was evidence, choose BRAVE; else rotate
        String last = recentFailures.peekLast();
        if ("INSUFFICIENT_EVIDENCE".equals(last)) return Plan.BRAVE;
        if ("TIMEOUT".equals(last)) return Plan.HYPER_NOVA;
        // rotate by current time
        int m = (int)((System.currentTimeMillis()/60000)%4);
        return Plan.values()[m];
    }

    public void beginAutoLearnCycle(){
        if (!props.isEnabled()) {
            log.debug("AutoLearn disabled");
            return;
        }
        ctx.begin();
        try {
            Plan plan = selectAutoLearnPlan();
            log.info("AutoLearn cycle start plan={}", plan);
            // load plan metadata if available
            try {
                if (planApplier != null) {
                    planApplier.resolvePlan(plan.name().toLowerCase() + ".v1", plan == Plan.BRAVE);
                }
            } catch (Exception e){
                log.debug("PlanApplier resolve failed (non-fatal): {}", e.toString());
            }

            // --- synthesize a candidate answer using local-only policy (placeholder) ---
            // In this scaffold we don't call an LLM; produce a structured stub that passes gates when configured permissively.
            String question = "Synthetic autolearn question at " + new Date();
            String answer = "This is a placeholder autolearn draft answer. [1] [2] [3]";
            List<String> citations = Arrays.asList("src:a","src:b","src:c");
            double quality = 0.95; // optimistic placeholder score

            // Quality gates
            if (!citationGate.allow(citations)) {
                recentFailures.add("INSUFFICIENT_EVIDENCE");
                cfvm.logAutoLearnFailure(question, "INSUFFICIENT_EVIDENCE", Map.of("plan", plan.name()));
                return;
            }
            if (!finalSigmoidGate.allow(quality)) {
                recentFailures.add("LOW_QUALITY");
                cfvm.logAutoLearnFailure(question, "LOW_QUALITY", Map.of("plan", plan.name()));
                return;
            }

            // Sidetrain checks
            String detourQ = detourSynthesizer.rephrase(question);
            String needleQ = needleProbeExecutor.extractKeyFactQuestion(answer);
            boolean consistent = sidetrainAuditor.consistent(answer, detourQ, needleQ);
            if (!sidetrainGate.allow(consistent)) {
                recentFailures.add("SIDETRAIN_INCONSISTENT");
                cfvm.logAutoLearnFailure(question, "SIDETRAIN_INCONSISTENT", Map.of("plan", plan.name()));
                return;
            }

            // Save to dataset
            File out = new File(props.getDataset().getPath());
            out.getParentFile().mkdirs();
            datasetWriter.appendRecord(out, question, answer);
            log.info("AutoLearn appended dataset record -> {}", out.getAbsolutePath());
        } finally {
            ctx.end();
        }
    }
}
