package com.example.lms.cfvm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parse well-known Java/Gradle/Maven compilation errors from plain text logs.
 * Produces {@link RawSlot} entries that CFVM can learn from.
 */
public final class BuildLogSlotExtractor implements RawSlotExtractor {

    // --- Common compile-time patterns (case-insensitive) ---
    private static final Pattern CANNOT_FIND_SYMBOL =
            Pattern.compile("(?i)cannot\\s+find\\s+symbol");
    private static final Pattern PACKAGE_DOES_NOT_EXIST =
            Pattern.compile("(?i)package\\s+.+\\s+does\\s+not\\s+exist");
    private static final Pattern CLASS_INTERFACE_ENUM_EXPECTED =
            Pattern.compile("(?i)(class|interface|enum|record)\\s+expected");
    private static final Pattern ILLEGAL_START_OF =
            Pattern.compile("(?i)illegal\\s+start\\s+of\\s+(type|expression)");
    private static final Pattern DUPLICATE_CLASS =
            Pattern.compile("(?i)duplicate\\s+class\\s+.+");
    private static final Pattern UNRECOGNIZED_TOKEN_ELLIPSIS =
            Pattern.compile("\\.\\.\\.|…"); // literal ellipsis often used as placeholder
    private static final Pattern METHOD_NOT_OVERRIDE =
            Pattern.compile("(?i)method\\s+does\\s+not\\s+override\\s+or\\s+implement");
    private static final Pattern GRADLE_MISSING_DEP =
            Pattern.compile("(?i)could not resolve|failed to collect dependencies");

    @Override
    public List<RawSlot> extract(String text) {
        final var out = new ArrayList<RawSlot>();
        if (text == null || text.isBlank()) return out;
        final var now = Instant.now();
        String[] lines = text.split("\\R");
        for (String line : lines) {
            if (CANNOT_FIND_SYMBOL.matcher(line).find()) {
                out.add(new RawSlot(now, "E_CANNOT_FIND_SYMBOL", line, RawSlot.Severity.ERROR));
            } else if (PACKAGE_DOES_NOT_EXIST.matcher(line).find()) {
                out.add(new RawSlot(now, "E_PACKAGE_NOT_FOUND", line, RawSlot.Severity.ERROR));
            } else if (CLASS_INTERFACE_ENUM_EXPECTED.matcher(line).find()) {
                out.add(new RawSlot(now, "E_TYPE_DECL_EXPECTED", line, RawSlot.Severity.ERROR));
            } else if (ILLEGAL_START_OF.matcher(line).find()) {
                out.add(new RawSlot(now, "E_ILLEGAL_START", line, RawSlot.Severity.ERROR));
            } else if (DUPLICATE_CLASS.matcher(line).find()) {
                out.add(new RawSlot(now, "E_DUPLICATE_CLASS", line, RawSlot.Severity.ERROR));
            } else if (UNRECOGNIZED_TOKEN_ELLIPSIS.matcher(line).find()) {
                out.add(new RawSlot(now, "E_PLACEHOLDER_ELLIPSIS", line, RawSlot.Severity.ERROR));
            } else if (METHOD_NOT_OVERRIDE.matcher(line).find()) {
                out.add(new RawSlot(now, "E_OVERRIDE_SIGNATURE", line, RawSlot.Severity.ERROR));
            } else if (GRADLE_MISSING_DEP.matcher(line).find()) {
                out.add(new RawSlot(now, "E_DEP_RESOLUTION", line, RawSlot.Severity.ERROR));
            }
        }
        if (out.isEmpty()) {
            out.add(new RawSlot(now, "I_NO_MATCH", "no compile pattern matched", RawSlot.Severity.INFO));
        }
        return out;
    }
}