package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** No provider calls: preserve optional Soniox without starting a process or installing dependencies. */
class SonioxInactivePreservationTest {
    @TempDir Path directory;
    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("conversate.enabled","true")
                .withProperty("conversate.asr.enabled","true")
                .withProperty("conversate.asr.provider","soniox")
                .withProperty("soniox.stt.enabled","true")
                .withProperty("soniox.api-key","synthetic-preservation-key")
                .withProperty("conversate.asr.sidecar.bootstrap","false")
                .withProperty("conversate.asr.sidecar.node",directory.resolve("absent-node").toString())
                .withProperty("conversate.asr.sidecar.runtime-directory",directory.resolve("runtime").toString());
    }
    private ConversateSttBudget budget() {
        return new ConversateSttBudget(new ObjectMapper(),true,directory.resolve("budget.jsonl").toString(),"verification","1","5");
    }
    private void assertDormant(MockEnvironment env, ConversateSttBudget budget) throws Exception {
        var manager=new SonioxSidecarManager(env,new ObjectMapper(),budget);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.setEnvironment(env);
            context.registerBean(SonioxSidecarManager.class,()->manager);
            context.registerBean("coreAvailability",Object.class,Object::new);
            assertDoesNotThrow(context::refresh);
            assertSame(manager,context.getBean(SonioxSidecarManager.class));
            assertNotNull(context.getBean("coreAvailability"));
            // Give the supervisor's immediate scheduled tick time to run; closing the
            // context immediately would hide an unwanted asynchronous process start.
            Thread.sleep(250);
            assertEquals("DISABLED",manager.diagnostics().get("state"));
            assertFalse(manager.ready());
            assertEquals(0,manager.childPid());
            assertEquals(0,((Number)manager.diagnostics().get("starts")).intValue());
            assertFalse(Files.exists(directory.resolve("runtime")));
            assertFalse(Files.exists(directory.resolve("budget.jsonl")));
            assertEquals("not_observed",manager.diagnostics().get("providerAttempt"));
        }
        assertFalse(manager.isRunning());
    }
    @Test void explicitOtherProviderDoesNotStartSonioxEvenWithValidConfiguration() throws Exception {
        for(String provider:List.of("local","deepgram","auto","unknown"))
            assertDormant(environment().withProperty("conversate.asr.provider",provider),budget());
    }
    @Test void missingAndPlaceholderKeysKeepSpringAvailableWithoutSidecarFiles() throws Exception {
        for(String key:List.of("","test","dummy","changeme","sk-local","${MISSING}"))
            assertDormant(environment().withProperty("soniox.api-key",key),budget());
    }
    @Test void disabledCaptureProviderOrSidecarAndMissingBudgetStayDormant() throws Exception {
        assertDormant(environment().withProperty("conversate.asr.enabled","false"),budget());
        assertDormant(environment().withProperty("soniox.stt.enabled","false"),budget());
        assertDormant(environment().withProperty("conversate.asr.sidecar.enabled","false"),budget());
        assertDormant(environment(),null);
    }
}
