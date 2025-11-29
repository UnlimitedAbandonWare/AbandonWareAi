package com.example.lms.cfvm.stable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class BuildLogSlotExtractor {
    private static final Pattern CANNOT_FIND_SYMBOL = Pattern.compile("(?i)cannot\s+find\s+symbol");
    private static final Pattern PACKAGE_NOT_EXIST = Pattern.compile("(?i)package\s+.+\s+does\s+not\s+exist");
    private static final Pattern OVERRIDE_MISMATCH = Pattern.compile("(?i)does\s+not\s+override|implement");
    private static final Pattern DUPLICATE_CLASS = Pattern.compile("(?i)duplicate\s+class");
    private static final Pattern ILLEGAL_ESCAPE = Pattern.compile("(?i)illegal\s+escape\s+character");

    public List<RawSlot> extract(String text){
        List<RawSlot> out = new ArrayList<>();
        if(text==null || text.isBlank()) return out;
        String[] lines = text.split("\R");
        for(String line: lines){
            if(CANNOT_FIND_SYMBOL.matcher(line).find()) out.add(new RawSlot(Instant.now(), "E_CANNOT_FIND_SYMBOL", line, RawSlot.Severity.ERROR));
            else if(PACKAGE_NOT_EXIST.matcher(line).find()) out.add(new RawSlot(Instant.now(),"E_PACKAGE_NOT_FOUND", line, RawSlot.Severity.ERROR));
            else if(OVERRIDE_MISMATCH.matcher(line).find()) out.add(new RawSlot(Instant.now(),"E_OVERRIDE_MISMATCH", line, RawSlot.Severity.ERROR));
            else if(DUPLICATE_CLASS.matcher(line).find()) out.add(new RawSlot(Instant.now(),"E_DUPLICATE_CLASS", line, RawSlot.Severity.ERROR));
            else if(ILLEGAL_ESCAPE.matcher(line).find()) out.add(new RawSlot(Instant.now(),"E_ILLEGAL_ESCAPE", line, RawSlot.Severity.ERROR));
        }
        return out;
    }
}