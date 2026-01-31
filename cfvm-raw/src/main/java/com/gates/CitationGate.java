package com.gates;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.gates.CitationGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.gates.CitationGate
role: config
*/
public class CitationGate {
    public boolean allow(List<String> citations, int min) {
        return citations != null && citations.stream().filter(Objects::nonNull).filter(s->!s.isBlank()).distinct().count() >= min;
    }
}