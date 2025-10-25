// src/main/java/com/example/lms/boot/VersionPurityCheck.java
package com.example.lms.boot;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Objects;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


/**
 * Perform a runtime check that all dev.langchain4j modules on the classpath
 * share the same expected version.  This complements the static Gradle BOM
 * alignment enforced during the build.  At startup the class loader is
 * inspected for packages beginning with {@code dev.langchain4j}.  If any

 * package has an implementation version that does not start with the
 * expected prefix (1.0.1), an IllegalStateException is thrown to abort
 * application startup.  This guard prevents inadvertent mixing of 0.2.x and
 * 1.0.x artifacts which can cause subtle classpath conflicts.
 */
@Component
public class VersionPurityCheck {
    private static final Logger log = LoggerFactory.getLogger(VersionPurityCheck.class);


    /**
     * The required version prefix for all LangChain4j modules.  Should align
     * with the BOM entry in build.gradle and any version declared in
     * application properties.  Changing this constant will change the
     * enforcement.
     */
    private static final String EXPECTED_PREFIX = "1.0.1";

    @PostConstruct
    public void verifyPackages() {
        // Enumerate all loaded packages and inspect those belonging to dev.langchain4j
        Package[] packages = Package.getPackages();
        boolean mismatchFound = false;
        StringBuilder bad = new StringBuilder();
        for (Package p : packages) {
            if (p == null) continue;
            String name = p.getName();
            if (name != null && name.startsWith("dev.langchain4j")) {
                String implVer = p.getImplementationVersion();
                // Some jars may not define implementation versions; treat null as OK
                if (implVer != null && !implVer.startsWith(EXPECTED_PREFIX)) {
                    mismatchFound = true;
                    bad.append(name).append(':').append(implVer).append(' ');
                }
            }
        }
        if (mismatchFound) {
            // Throw with details about offending packages.  This stops the
            // container from starting so that the operator can fix the
            // dependency alignment before running.
            throw new IllegalStateException(
                    "LangChain4j version conflict detected. Expected " + EXPECTED_PREFIX + ".* but found: " + bad.toString().trim());
        }
        // Log success for troubleshooting
        if (log.isInfoEnabled()) {
            String modules = Arrays.stream(packages)
                    .filter(Objects::nonNull)
                    .map(Package::getName)
                    .filter(s -> s != null && s.startsWith("dev.langchain4j"))
                    .sorted()
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);
            log.info("LangChain4j runtime version purity check OK. Modules: {}", modules);
        }
    }
}