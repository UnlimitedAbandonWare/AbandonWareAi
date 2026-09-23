package ai.abandonware.nova.orch.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ChunkEnvelopeTest {

    @Test
    void chunkEnvelopeDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/chunk/ChunkEnvelope.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}", Pattern.DOTALL)
                .matcher(source)
                .find());
    }

    @Test
    void numericEnvelopeParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/chunk/ChunkEnvelope.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertParserCatchNarrowed(source, "return Integer.parseInt(s.trim());");
        assertParserCatchNarrowed(source, "return Integer.parseInt(m.group(1));");
        assertTrue(source.contains("private static String errorType(Throwable error)"));
        assertTrue(source.contains("error instanceof NumberFormatException"));
        assertTrue(source.contains("return \"invalid_number\";"));
        assertFalse(source.contains("return error == null ? \"unknown\" : error.getClass().getSimpleName();"));
    }

    @Test
    void parsesBracketAndJsonChunkEnvelopes() {
        ChunkEnvelope bracket = ChunkEnvelope.parse("[CHUNK 2/5] payload");

        assertTrue(bracket.explicit());
        assertNotNull(bracket.meta());
        assertEquals(2, bracket.meta().idx());
        assertEquals(5, bracket.meta().total());
        assertEquals("payload", bracket.payload());

        ChunkEnvelope json = ChunkEnvelope.parse(
                ChunkEnvelope.PREFIX + "{\"idx\":3,\"total\":9,\"doc\":\"d1\",\"chunkId\":\"c7\"}\nbody");

        assertTrue(json.explicit());
        assertNotNull(json.meta());
        assertEquals(3, json.meta().idx());
        assertEquals(9, json.meta().total());
        assertEquals("d1", json.meta().doc());
        assertEquals("c7", json.meta().chunkId());
        assertEquals("body", json.payload());
    }

    private static void assertParserCatchNarrowed(String source, String parserCall) {
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, () -> "parser call should be locatable: " + parserCall);
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "numeric chunk-envelope parser fallbacks must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "numeric chunk-envelope parser fallbacks should catch only NumberFormatException");
    }
}
