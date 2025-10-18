package com.abandonware.ai.gates;

import java.util.*;
import java.util.Objects;

public class CitationGate {
    public boolean allow(List<String> citations, int min) {
        return citations != null && citations.stream().filter(Objects::nonNull).filter(s->!s.isBlank()).distinct().count() >= min;
    }
}