package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.orch.aop.MemoryDegradedAspect;
import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.PendingMemoryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryDegradedConditionalWiringTest {
    @TempDir Path directory;
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(NovaOrchestrationAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("nova.orch.web-analyze-retriever.override.enabled=false",
                        "nova.orch.chunking.enabled=false",
                        "nova.orch.degraded-storage.path=" + directory.resolve("pending.jsonl"));
    }

    @Test void defaultStorageRetainsAspect() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DegradedStorage.class).hasSingleBean(MemoryDegradedAspect.class);
        });
    }

    @Test void absentStorageDoesNotCreateAnUnusableAspect() {
        runner().withPropertyValues("nova.orch.degraded-storage.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DegradedStorage.class).doesNotHaveBean(MemoryDegradedAspect.class);
        });
    }

    @Test void customStorageRemainsSupportedAndAspectSwitchStillWins() {
        var custom = runner().withPropertyValues("nova.orch.degraded-storage.enabled=false")
                .withBean(DegradedStorage.class, InMemoryStorage::new);
        custom.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MemoryDegradedAspect.class);
        });
        custom.withPropertyValues("nova.orch.memory-degraded.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DegradedStorage.class).doesNotHaveBean(MemoryDegradedAspect.class);
        });
    }

    private static final class InMemoryStorage implements DegradedStorage {
        private final List<PendingMemoryEvent> pending = new ArrayList<>();
        @Override public void putPending(PendingMemoryEvent event) { pending.add(event); }
        @Override public List<PendingMemoryEvent> drain(int max) {
            List<PendingMemoryEvent> result = List.copyOf(pending.subList(0, Math.min(max, pending.size())));
            pending.subList(0, result.size()).clear();
            return result;
        }
    }
}
