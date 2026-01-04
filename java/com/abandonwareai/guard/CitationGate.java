package com.abandonwareai.guard;

import org.springframework.stereotype.Component;

@Component
public class CitationGate {
    public boolean pass(int citations){ return citations>=3; }

}