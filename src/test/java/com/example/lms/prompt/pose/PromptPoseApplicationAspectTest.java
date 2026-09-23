package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.ensemble.StochasticParamSampler;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.guard.SensitiveTopicDetector;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptPoseApplicationAspectTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void chatEntryAppliesZero100SelfAskTemperatureAndCitationOverridesBeforeProceeding() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        PromptPoseApplicationAspect aspect = new PromptPoseApplicationAspect(
                props,
                new PromptPoseApplicationJudge(props, null));
        GuardContext ctx = new GuardContext();
        GuardContextHolder.set(ctx);
        String raw = "PromptPose 응용 탐색을 과감하게 넓히고 문체 변주도 같이 조율해줘";

        Object out = aspect.aroundChatEntry(new FakePjp("ok", raw));

        assertEquals("ok", out);
        assertTrue(ctx.planBool("search.zero100.enabled", false));
        assertTrue(ctx.planInt("search.zero100.queryBurstMax", 0) >= 12);
        assertEquals(3, ctx.planInt("expand.selfAsk.count", 0));
        assertTrue(ctx.planDouble("llm.answer.temperature", 0.0d) > 0.20d);
        assertTrue(ctx.getMinCitations() >= 2);
        assertTrue(ctx.planBool("overdrive.enabled", false));
        assertTrue(ctx.isCompressionMode());
        assertEquals(Boolean.TRUE, TraceStore.get(PromptPoseTrace.APPLICATION_APPLIED));
        assertEquals("explore", TraceStore.get(PromptPoseTrace.APPLICATION_INTENT_SLOT));
        assertTrue(ctx.planBool("creative.emergence.active", false));
        assertTrue(java.util.Set.of("VIVID", "WILD", "FERAL")
                .contains(String.valueOf(ctx.getPlanOverride("creative.emergence.profile"))));
        assertFalse(TraceStore.getAll().toString().contains(raw));
    }

    @Test
    void deterministicProfileIsRequestLocalAndMixedEvidenceNeverActivatesIt() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        StochasticParamSampler fixed = new StochasticParamSampler() {
            @Override
            public boolean applyCreativeProfile(GuardContext context) {
                applyWildProfile(context);
                return true;
            }
        };
        PromptPoseApplicationAspect aspect = new PromptPoseApplicationAspect(
                props, new PromptPoseApplicationJudge(props, null), fixed);
        GuardContext creative = new GuardContext();
        GuardContextHolder.set(creative);

        aspect.aroundChatEntry(new FakePjp("ok", "brainstorm a creative visual story idea"));

        assertTrue(creative.planBool("creative.emergence.active", false));
        assertEquals("WILD", creative.getPlanOverride("creative.emergence.profile"));
        assertEquals(1.31d, creative.planDouble("creative.emergence.final.temperature", 0.0d));
        assertEquals(0.98d, creative.planDouble("creative.emergence.final.topP", 0.0d));
        assertEquals("hash:0123456789ab", creative.getPlanOverride("creative.emergence.requestedOptionsHash"));

        GuardContext constrained = new GuardContext();
        GuardContextHolder.set(constrained);
        aspect.aroundChatEntry(new FakePjp("ok",
                "brainstorm creatively but verify with official evidence and citations"));

        assertFalse(constrained.planBool("creative.emergence.active", false));
        assertEquals(null, constrained.getPlanOverride("creative.emergence.profile"));
        assertTrue(constrained.planDouble("llm.answer.temperature.max", 2.0d) <= 0.35d);
    }

    @Test
    void applicationJudgeFailureUsesStableTraceLabelAndStillProceeds() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        PromptPoseApplicationAspect aspect = new PromptPoseApplicationAspect(props, new ThrowingJudge(props));

        Object out = aspect.aroundChatEntry(new FakePjp("ok", "PromptPose application failure path"));

        assertEquals("ok", out);
        assertEquals("prompt_pose_application_failed", TraceStore.get("promptPose.application.failureClass"));
        assertFalse(String.valueOf(TraceStore.get("promptPose.application.failureClass"))
                .contains("IllegalStateException"));
    }

    @Test
    void reusedContextCannotKeepProfileAfterNextDrawFails() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        AtomicInteger draws = new AtomicInteger();
        StochasticParamSampler sampler = new StochasticParamSampler() {
            @Override
            public boolean applyCreativeProfile(GuardContext context) {
                if (draws.getAndIncrement() == 0) {
                    applyWildProfile(context);
                    return true;
                }
                return false;
            }
        };
        PromptPoseApplicationAspect aspect = new PromptPoseApplicationAspect(
                props, new PromptPoseApplicationJudge(props, null), sampler);
        GuardContext reused = new GuardContext();
        GuardContextHolder.set(reused);

        aspect.aroundChatEntry(new FakePjp("first", "brainstorm a creative city"));
        assertTrue(reused.planBool("creative.emergence.active", false));

        aspect.aroundChatEntry(new FakePjp("second", "brainstorm a creative garden"));

        assertFalse(reused.planBool("creative.emergence.active", true));
        assertEquals(null, reused.getPlanOverride("creative.emergence.profile"));
        assertEquals(null, reused.getPlanOverride("creative.emergence.final.temperature"));
        assertEquals("invalid-profile-draw", reused.getPlanOverride("creative.emergence.suppressedReason"));
    }

    @Test
    void promptPoseOptOutClearsAnyPreviouslyActiveRequestProfile() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        PromptPoseApplicationAspect aspect = new PromptPoseApplicationAspect(
                props, new PromptPoseApplicationJudge(props, null));
        GuardContext reused = new GuardContext();
        GuardContextHolder.set(reused);
        aspect.aroundChatEntry(new FakePjp("first", "brainstorm a creative city"));
        assertTrue(reused.planBool("creative.emergence.active", false));

        props.setEnabled(false);
        aspect.aroundChatEntry(new FakePjp("second", "ordinary request"));

        assertFalse(reused.planBool("creative.emergence.active", true));
        assertEquals(null, reused.getPlanOverride("creative.emergence.profile"));
    }

    @Test
    void directChatEntryAppliesSharedSensitiveGuardBeforeCreativeProfile() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        PromptPoseApplicationAspect aspect = new PromptPoseApplicationAspect(
                props, new PromptPoseApplicationJudge(props, null));
        SensitiveTopicDetector detector = new SensitiveTopicDetector();
        ReflectionTestUtils.setField(detector, "enabled", true);
        ReflectionTestUtils.setField(detector, "answerTemp", 0.2d);
        ReflectionTestUtils.setField(detector, "exploreTempCap", 0.7d);
        ReflectionTestUtils.setField(aspect, "sensitiveTopicDetector", detector);
        GuardContext context = new GuardContext();
        GuardContextHolder.set(context);

        aspect.aroundChatEntry(new FakePjp("ok", "brainstorm anxiety coping ideas"));

        assertTrue(context.isSensitiveTopic());
        assertFalse(context.planBool("creative.emergence.active", true));
        assertTrue(context.planDouble("llm.answer.temperature.max", 2.0d) <= 0.2d);
        assertEquals("evidence_strict", context.getPlanOverride("promptPose.application.intentSlot"));
    }

    @Test
    void privacyBoundarySuppressesCreativeProfileEvenWithoutSensitiveTopicFlag() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        PromptPoseApplicationAspect aspect = new PromptPoseApplicationAspect(
                props, new PromptPoseApplicationJudge(props, null));
        GuardContext context = new GuardContext();
        context.putPlanOverride("privacy.boundary.enforce", true);
        GuardContextHolder.set(context);

        aspect.aroundChatEntry(new FakePjp("ok", "brainstorm a creative city"));

        assertFalse(context.planBool("creative.emergence.active", true));
    }

    private static void applyWildProfile(GuardContext context) {
        context.putPlanOverride("creative.emergence.profile", "WILD");
        context.putPlanOverride("creative.emergence.search.temperature", 0.94d);
        context.putPlanOverride("creative.emergence.search.rate", 0.80d);
        context.putPlanOverride("creative.emergence.candidate.temperature", 1.36d);
        context.putPlanOverride("creative.emergence.candidate.topP", 0.98d);
        context.putPlanOverride("creative.emergence.final.temperature", 1.31d);
        context.putPlanOverride("creative.emergence.final.topP", 0.98d);
        context.putPlanOverride("creative.emergence.selfAsk.temperature", 0.93d);
        context.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
    }

    private static final class ThrowingJudge extends PromptPoseApplicationJudge {
        private ThrowingJudge(PromptPoseProperties props) {
            super(props, null);
        }

        @Override
        public PromptPoseApplicationDecision decide(PromptPoseInputSanitizer.SanitizedInput input,
                                                   int requestedMaxQueries) {
            throw new IllegalStateException("judge failed ownerToken=secret-prompt-pose");
        }
    }

    private static final class FakePjp implements ProceedingJoinPoint {
        private final Object result;
        private final Object[] args;

        private FakePjp(Object result, Object... args) {
            this.result = result;
            this.args = args;
        }

        @Override
        public Object proceed() {
            return result;
        }

        @Override
        public Object proceed(Object[] args) {
            return result;
        }

        @Override
        public void set$AroundClosure(AroundClosure arc) {
        }

        @Override
        public Object getThis() {
            return this;
        }

        @Override
        public Object getTarget() {
            return this;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public Signature getSignature() {
            return null;
        }

        @Override
        public SourceLocation getSourceLocation() {
            return null;
        }

        @Override
        public String getKind() {
            return "method-execution";
        }

        @Override
        public JoinPoint.StaticPart getStaticPart() {
            return null;
        }

        @Override
        public String toShortString() {
            return "FakePjp";
        }

        @Override
        public String toLongString() {
            return "FakePjp";
        }
    }
}
