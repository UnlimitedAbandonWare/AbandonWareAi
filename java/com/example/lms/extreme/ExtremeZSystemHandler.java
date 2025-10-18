
package com.example.lms.extreme;

import java.util.*;


public class ExtremeZSystemHandler {
    public List<String> explode(String query) {
        // generate anchor-based sub-queries
        List<String> out = new ArrayList<>();
        out.add(query + " site:wikipedia.org");
        out.add(query + " filetype:pdf");
        out.add(query + " 최신 소식");
        return out;
    }
}