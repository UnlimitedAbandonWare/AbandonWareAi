package com.abandonware.ai.agent.integrations;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIntegrationParserContractTest {

    private static final Pattern BROAD_CATCH =
            Pattern.compile("catch\\s*\\(\\s*(Exception|Throwable)\\b");

    @Test
    void scoreParsersOnlyCatchNumberFormatException() throws Exception {
        List<ParserTarget> targets = List.of(
                new ParserTarget(
                        "main/java/com/abandonware/ai/agent/integrations/ColbertReranker.java",
                        "Double.parseDouble(String.valueOf(o))"),
                new ParserTarget(
                        "main/java/com/abandonware/ai/agent/integrations/ColbertLiteReranker.java",
                        "Double.parseDouble(String.valueOf(o))"),
                new ParserTarget(
                        "main/java/com/abandonware/ai/agent/integrations/HybridRetriever.java",
                        "Double.parseDouble(String.valueOf(o))"),
                new ParserTarget(
                        "main/java/com/abandonware/ai/agent/integrations/SbertReranker.java",
                        "Double.parseDouble(String.valueOf(o))"),
                new ParserTarget(
                        "main/java/com/abandonware/ai/agent/integrations/SbertPreindexedReranker.java",
                        "Double.parseDouble(String.valueOf(o))"));

        for (ParserTarget target : targets) {
            assertParserCatchNarrowed(target);
        }
    }

    private static void assertParserCatchNarrowed(ParserTarget target) throws Exception {
        String source = Files.readString(Path.of(target.file()));
        int parse = source.indexOf(target.parserCall());

        assertTrue(parse >= 0, "parser call should remain visible in " + target.file());
        String window = source.substring(parse, Math.min(source.length(), parse + 220));
        assertFalse(BROAD_CATCH.matcher(window).find(),
                "score parser must not swallow broad failures in " + target.file());
        assertTrue(window.contains("catch(NumberFormatException")
                        || window.contains("catch (NumberFormatException"),
                "score parser should only catch NumberFormatException in " + target.file());
    }

    private record ParserTarget(String file, String parserCall) {
    }
}
