package com.example.lms;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import static org.junit.jupiter.api.Assertions.*;


/**
 * Hard gate test ensuring that only the LangChain4j 1.0.1 library is on the
 * classpath.  Any presence of 0.2.x versions will cause this test to fail.
 * When a conflict is detected the test prints the offending jar path to
 * facilitate debugging.  See the project specification for the
 * required behaviour of the LangChain version gate.
 */
public class LangchainVersionTest {

    @Test
    public void testLangchain4jVersionPurity() throws IOException {
        // Attempt to locate all class definitions for ChatModel.  This class
        // exists in the langchain4j library and is expected to come from the
        // 1.0.1 version.  If a 0.2.x version is also present the class
        // loader may return multiple locations.
        Enumeration<URL> urls = Thread.currentThread().getContextClassLoader()
                .getResources("dev/langchain4j/model/chat/ChatModel.class");
        Set<String> jars = new HashSet<>();
        while (urls.hasMoreElements()) {
            URL u = urls.nextElement();
            String spec = u.toString();
            // e.g. jar:file:/path/to/langchain4j-1.0.1.jar!/dev/langchain4j/model/chat/ChatModel.class
            if (spec.startsWith("jar:")) {
                int bang = spec.indexOf("!");
                if (bang > 4) {
                    String jarPath = spec.substring(4, bang);
                    jars.add(jarPath);
                }
            }
        }
        Pattern versionPattern = Pattern.compile("langchain4j[-.]([0-9]+\\.[0-9]+\\.[0-9]+)");
        for (String jar : jars) {
            String fileName = jar.substring(jar.lastIndexOf('/') + 1);
            Matcher m = versionPattern.matcher(fileName);
            if (m.find()) {
                String version = m.group(1);
                // Accept only version 1.0.1
                if (!"1.0.1".equals(version)) {
                    System.err.printf("langchain4j version conflict: dev.langchain4j:%s at %s%n", version, jar);
                    fail("Detected unsupported langchain4j version " + version);
                }
            }
        }
    }
}