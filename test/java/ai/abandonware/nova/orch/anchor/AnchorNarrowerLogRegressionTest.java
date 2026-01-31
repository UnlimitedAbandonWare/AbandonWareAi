package ai.abandonware.nova.orch.anchor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnchorNarrowerLogRegressionTest {

    @Test
    void pick_shouldNotReturnPoliteRequestAsAnchor_term() {
        var an = new AnchorNarrower();

        var anchor = an.pick("갤럭시 트라이 폴드 사양좀 알려줘봐.", List.of(), null);

        assertNotNull(anchor);
        assertNotNull(anchor.term());
        // Regression: do not pick the polite request token as the anchor.
        assertNotEquals("알려줘봐", anchor.term());
        assertFalse(anchor.term().contains("알려줘봐"));
        // Expected: keep the meaningful entity phrase.
        assertEquals("갤럭시 트라이 폴드", anchor.term());
    }

    @Test
    void cheapVariants_shouldNotIncludeBarePolitePhrase() {
        var an = new AnchorNarrower();

        var q = "갤럭시 트라이 폴드 사양좀 알려줘봐.";
        var anchor = an.pick(q, List.of(), null);

        var vars = an.cheapVariants(q, anchor, 3);
        assertNotNull(vars);
        assertTrue(vars.size() <= 3);
        assertFalse(vars.stream().anyMatch(v -> v != null && v.trim().equals("알려줘봐")));
    }
}
