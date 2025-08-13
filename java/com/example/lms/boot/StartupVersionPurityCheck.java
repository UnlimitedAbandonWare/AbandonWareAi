// src/main/java/com/example/lms/boot/StartupVersionPurityCheck.java
package com.example.lms.boot;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

@Slf4j
@Component
public class StartupVersionPurityCheck {

    private static final String EXPECTED_PREFIX = "1.0.1"; // LangChain4j 고정

    @PostConstruct
    public void verify() {
        try {
            Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
            Set<String> seen = new TreeSet<>();
            while (resources.hasMoreElements()) {
                Manifest mf = new Manifest(resources.nextElement().openStream());
                Attributes at = mf.getMainAttributes();
                String title = at.getValue("Implementation-Title");
                String ver   = at.getValue("Implementation-Version");
                if (title != null && title.startsWith("langchain4j")) {
                    seen.add(title + ":" + ver);
                    if (ver != null && !ver.startsWith(EXPECTED_PREFIX)) {
                        throw new IllegalStateException(
                                "Mixed LangChain4j detected: " + title + ":" + ver +
                                        " (expected " + EXPECTED_PREFIX + "). Purge old artifacts.");
                    }
                }
            }
            log.info("LangChain4j purity OK: {}", seen);
        } catch (Exception e) {
            throw new IllegalStateException("Failed classpath purity check", e);
        }
    }
}
