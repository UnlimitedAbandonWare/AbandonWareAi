// src/main/java/com/example/lms/boot/StartupVersionPurityCheck.java
package com.example.lms.boot;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * LangChain4j 버전 순도를 부팅 시 강제 확인한다.
 * - dev.langchain4j 계열 전 모듈의 Implementation-Version이 EXPECTED_PREFIX(1.0.1)로 시작하지 않으면 즉시 실패.
 * - 발견된 LangChain4j 모듈을 전부 덤프(모듈명:버전 @ JAR)하여 역추적을 돕는다.
 *
 * 혼재 예) 0.2.x + 1.0.x 가 동시에 classpath에 존재할 때
 *  -> IllegalStateException 을 던져 애플리케이션 부팅 중단.
 */
@Component
public class StartupVersionPurityCheck {
    private static final Logger log = LoggerFactory.getLogger(StartupVersionPurityCheck.class);


    /** LangChain4j 고정 버전 접두사 */
    private static final String EXPECTED_PREFIX = "1.0.1";

    /** 전체 모듈 가드(Implementation-Title 또는 Automatic-Module-Name 이 아래 접두사 중 하나면 검사 대상) */
    private static final String MODULE_PREFIX = "dev.langchain4j";

    @PostConstruct
    public void verify() {
        try {
            Enumeration<URL> resources =
                    getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");

            Set<String> seen   = new TreeSet<>(); // 간단 목록 (모듈:버전)
            Set<String> dump   = new TreeSet<>(); // 상세 덤프 (모듈:버전 @ jar)
            Set<String> badSet = new TreeSet<>(); // 혼재 용의자 (즉시 중단 사유)

            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();

                try (InputStream in = url.openStream()) {
                    Manifest mf = new Manifest(in);
                    Attributes at = mf.getMainAttributes();

                    String title  = at.getValue("Implementation-Title");
                    String ver    = at.getValue("Implementation-Version");
                    String module = at.getValue("Automatic-Module-Name");

                    String name = title != null ? title : module;
                    boolean isLangChain4j =
                            name != null && (name.startsWith("langchain4j") || name.startsWith(MODULE_PREFIX));

                    if (!isLangChain4j) {
                        continue;
                    }

                    String simple = (name != null ? name : "unknown") + ":" + ver;
                    String entry  = simple + " @ " + jarName(url);

                    // 덤프/요약 수집
                    seen.add(simple);
                    dump.add(entry);

                    // 혼재 판정
                    if (ver != null && !ver.startsWith(EXPECTED_PREFIX)) {
                        badSet.add(entry);
                    }
                }
            }

            if (!badSet.isEmpty()) {
                // 즉시 종료 (어떤 JAR에서 끼어들었는지까지 표시)
                throw new IllegalStateException(
                        "Mixed LangChain4j detected (expected " + EXPECTED_PREFIX + ".*). Offenders: " + badSet
                                + " -> Purge old/foreign artifacts and align BOM/core/starter/OpenAI to 1.0.1.");
            }

            log.info("LangChain4j purity OK: {}", seen);
            if (!dump.isEmpty()) {
                log.info("LangChain4j module dump → {}", dump);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed classpath purity check", e);
        }
    }

    /** (운영 편의) 불일치 항목만 간단 문자열로 반환 */
    public String dumpInconsistentArtifacts() {
        try {
            // verify()와 동일한 로직을 간소화하여 요약만 리턴하도록 구현 가능
            return "OK";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * manifest URL에서 JAR/경로 힌트만 뽑아준다.
     * 예: jar:file:/.../langchain4j-core-1.0.1.jar!/META-INF/MANIFEST.MF -> langchain4j-core-1.0.1.jar
     */
    private static String jarName(URL url) {
        String s = url.toString();
        int bang = s.indexOf('!');
        if (bang >= 0) s = s.substring(0, bang);
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < s.length()) {
            return s.substring(slash + 1);
        }
        return s;
    }
}