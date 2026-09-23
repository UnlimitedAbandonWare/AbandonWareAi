package com.abandonware.ai.agent.consent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentContextTest {

    @Test
    void attributesAreImmutableSnapshot() {
        Map<String, Object> source = new HashMap<>();
        source.put("scope", "internal.enqueue");

        ConsentContext context = new ConsentContext(source);
        source.put("scope", "admin.override");

        assertThat(context.attributes()).containsEntry("scope", "internal.enqueue");
        assertThatThrownBy(() -> context.attributes().put("scope", "mutated"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullAttributesBecomeEmptyMap() {
        ConsentContext context = new ConsentContext(null);

        assertThat(context.attributes()).isEmpty();
    }
}
