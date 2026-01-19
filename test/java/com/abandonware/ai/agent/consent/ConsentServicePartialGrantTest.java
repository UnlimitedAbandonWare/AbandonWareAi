package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.scope.ToolScope;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;




import static org.assertj.core.api.Assertions.*;

public class ConsentServicePartialGrantTest {

    @Test
    void ensureGranted_requestsOnlyMissingScopes() {
        var store = new InMemoryConsentStore(Set.of(ToolScope.READ_PROFILE));
        var service = new BasicConsentService(store);
        var token = ConsentToken.of("user-1");
        var ctx = new ConsentContext(java.util.Map.of("channel","kakao"));

        assertThatThrownBy(() ->
                service.ensureGranted(token,
                        new ToolScope[]{ToolScope.READ_PROFILE, ToolScope.SEND_MESSAGE},
                        ctx))
            .isInstanceOf(ConsentRequiredException.class)
            .satisfies(ex -> {
                var cre = (ConsentRequiredException) ex;
                assertThat(cre.missing()).containsExactly(ToolScope.SEND_MESSAGE);
                assertThat(cre.ttlSeconds()).isGreaterThan(0);
                assertThat(cre.hints().get("channel")).isEqualTo("kakao");
            });
    }

    static class InMemoryConsentStore implements ConsentStore {
        private final Set<ToolScope> granted;
        InMemoryConsentStore(Set<ToolScope> granted){ this.granted = granted; }
        @Override public boolean has(ConsentToken token, ToolScope scope){ return granted.contains(scope); }
    }
}