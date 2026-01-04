package com.example.lms.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Collection;

@Component
public class CitationGate {

    @Value("${gate.minCitations:3}")
    private int minCitations;

    public boolean allow(Collection<?> citationsTrusted){
        int n = (citationsTrusted==null?0:citationsTrusted.size());
        return n >= minCitations;
    }
}