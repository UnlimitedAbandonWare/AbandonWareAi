package com.abandonware.ai.gates;

import java.util.*;
import java.util.Objects;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.gates.CitationGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.gates.CitationGate
role: config
*/
public class CitationGate {
    public boolean allow(List<String> citations, int min) {
        return citations != null && citations.stream().filter(Objects::nonNull).filter(s->!s.isBlank()).distinct().count() >= min;
    }
}