package com.example.lms.service.rag.fusion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedRrfFuserTest {

    @Test
    void nonFiniteSystemPropertyWeightFallsBackToDefault() {
        System.setProperty("rag.fusion.weight.locale", "Infinity");
        try {
            double weight = WeightedRrfFuser.weight(Locale.KOREA,
                    new WeightedRrfFuser.Result("news.example.kr", "title", "snippet"));

            assertEquals(0.40d, weight);
        } finally {
            System.clearProperty("rag.fusion.weight.locale");
        }
    }

    @Test
    void systemPropertyWeightParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/fusion/WeightedRrfFuser.java"))
                .replace("\r\n", "\n");

        assertFalse(source.contains("catch (Exception e) {\n            return defaultValue;\n        }"));
        assertTrue(source.contains("catch (NumberFormatException e)"));
        assertTrue(source.contains("log.debug(\"[WeightedRrfFuser] fail-soft stage={} errorType={}\",")
                        && source.contains("\"systemPropertyWeight\", \"invalid_number\""),
                "system property weight parser should use stable invalid_number error label");
    }
}
