package com.example.lms.service.guard;

import java.util.*;
public class CitationGate {
    public boolean ok(List<String> sources, int minCount, double allowlistRatio){
        if (sources==null) return false;
        int n = sources.size();
        if (n < minCount) return false;
        // naive: approve
        return true;
    }
}
