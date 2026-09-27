package ai.abandonware.nova.orch.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RollingSummaryEnvelopeBomWhitespaceTest {

    @Test
    void parsesEnvelopeWhenWhitespacePrecedesBom() {
        RollingSummaryEnvelope parsed = RollingSummaryEnvelope.parse(
                " \uFEFF" + RollingSummaryEnvelope.PREFIX + "{\"v\":1}\nsummary");

        assertNotNull(parsed);
        assertEquals("{\"v\":1}", parsed.metaJson());
        assertEquals("summary", parsed.summary());
    }
}
