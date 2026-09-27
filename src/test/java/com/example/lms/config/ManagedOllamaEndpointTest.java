package com.example.lms.config;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ManagedOllamaEndpointTest {
    @TempDir Path directory;
    @Test void concurrentLaunchIsSerializedAndRestartReuseRequiresExactExecutionAndSelection() throws Exception {
        String host;
        try (var first = ManagedOllamaEndpoint.acquire(directory, "127.0.0.1:11435", "GPU-A")) {
            host = first.host();
            first.register(host, 123, 456);
            assertThatThrownBy(() -> ManagedOllamaEndpoint.acquire(directory, "localhost:11435", "GPU-A"))
                    .isInstanceOf(java.io.IOException.class);
        }
        try (var restarted = ManagedOllamaEndpoint.acquire(directory, "127.0.0.1:11435", "GPU-A")) {
            assertThat(restarted.host()).isEqualTo(host);
            assertThat(restarted.registered(host, 123, 456)).isTrue();
            assertThat(restarted.registered(host, 123, 457)).isFalse();
            assertThat(restarted.registered(host, 124, 456)).isFalse();
            assertThat(restarted.registered("127.0.0.1:11435", 123, 456)).isFalse();
        }
        try (var changedGpu = ManagedOllamaEndpoint.acquire(directory, "127.0.0.1:11435", "GPU-B")) {
            assertThat(changedGpu.registered(host, 123, 456)).isFalse();
        }
    }
    @Test void remoteEndpointNeverAcquiresLocalLaunchAuthority() {
        assertThatThrownBy(() -> ManagedOllamaEndpoint.acquire(directory, "example.com:11435", "GPU-A"))
                .isInstanceOf(java.io.IOException.class);
    }
}
