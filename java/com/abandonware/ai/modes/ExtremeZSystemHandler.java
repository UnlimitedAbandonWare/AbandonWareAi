package com.abandonware.ai.modes;

import java.util.*;

/** ExtremeZ: expand a query into many sub-queries for broad recall. */
public class ExtremeZSystemHandler {
    public List<String> expand(String query, int n) {
        List<String> out = new ArrayList<>();
        for (int i=0;i<n;i++) out.add(query + " #" + (i+1));
        return out;
    }
}