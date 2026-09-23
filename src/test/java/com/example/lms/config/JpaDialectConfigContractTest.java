package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaDialectConfigContractTest {

    @Test
    void h2DialectIsInferredUnlessExplicitlyOverriddenByEnvironment() throws Exception {
        List<Path> activeConfigs = List.of(
                Path.of("main/resources/application.properties"),
                Path.of("main/resources/application-local.yml"),
                Path.of("main/resources/application-dev.yml"),
                Path.of("main/resources/application-desktop-gpu-node.yml"),
                Path.of("main/resources/application-macmini-control-plane.yml"));

        for (Path config : activeConfigs) {
            String text = Files.readString(config, StandardCharsets.UTF_8);
            assertFalse(text.contains(":org.hibernate.dialect.H2Dialect}"),
                    config + " should not default to explicit H2Dialect; let Hibernate infer H2 from JDBC URL");
        }

        assertTrue(Files.readString(Path.of("main/resources/application.properties"), StandardCharsets.UTF_8)
                .contains("spring.jpa.database-platform=${LMS_DB_DIALECT:}"));
    }
}
