package com.example.lms.version;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Enforce LangChain version pin (generic to repo layout).
 * Set -Dlangchain.version=1.0.1 or export LANGCHAIN_VERSION=1.0.1
 */
public class LangchainVersionTest {

    @Test
    void mustBe_1_0_1() {
        String v = System.getProperty("langchain.version");
        if (v == null || v.isBlank()) {
            v = System.getenv("LANGCHAIN_VERSION");
        }
        assertEquals("1.0.1", v, "Pin LangChain to 1.0.1 via -Dlangchain.version or ENV LANGCHAIN_VERSION");
    }
}