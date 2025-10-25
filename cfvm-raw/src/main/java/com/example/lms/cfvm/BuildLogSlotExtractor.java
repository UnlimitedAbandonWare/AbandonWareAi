package com.example.lms.cfvm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * [GPT-PRO-AGENT v2] — concise navigation header (no runtime effect).
 * Module: com.example.lms.cfvm.BuildLogSlotExtractor
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.cfvm.BuildLogSlotExtractor
role: config
*/
public class BuildLogSlotExtractor implements RawSlotExtractor {

    // --- Compile error patterns ---
    private static final Pattern MISSING_SYMBOL = Pattern.compile("(?i)cannot\\s+find\\s+symbol");
    private static final Pattern DUPLICATE_CLASS = Pattern.compile("(?i)duplicate\\s+class\\s+.+");
    private static final Pattern ILLEGAL_START = Pattern.compile("(?i)illegal\\s+start\\s+of\\s+(type|expression)");
    private static final Pattern CLASS_EXPECTED = Pattern.compile("(?i)class,\\s*interface,\\s*enum,?\\s*or\\s*record\\s+expected");
    private static final Pattern PACKAGE_NOT_FOUND = Pattern.compile("(?i)package\\s+.+\\s+does\\s+not\\s+exist");
    private static final Pattern ILLEGAL_ESCAPE_CHAR = Pattern.compile("(?i)illegal\\s+escape\\s+character");
    private static final Pattern BAD_HYPHEN_ESCAPE = Pattern.compile("(?i)invalid\\s+escape\\s+.*-.*");
    private static final Pattern SPLIT_RAW_WS = Pattern.compile("(?i)pattern\\s+.*\\\[?\\\\s\\+.*"); // misuse like .split("\s+"?) hints
    private static final Pattern BEAN_NAME_CONFLICT = Pattern.compile("(?i)ConflictingBeanDefinitionException:.*bean name '([^']+)'");
    private static final Pattern CLASS_NOT_FOUND = Pattern.compile("(?i)class\\s+not\\s+found:.*");
    private static final Pattern CNF_BM25_INDEX = Pattern.compile("(?i)cannot\\s+find\\s+symbol.*Bm25LocalIndex");

    @Override
    public List<RawSlot> extract(Throwable ex, RawSlot.Stage stage, String sessionId) {
        String msg = (ex == null ? "" : String.valueOf(ex.getMessage()));
        return fromLine(msg, stage, sessionId);
    }

    /** Extract zero or more slots from a single build log line. */
    public List<RawSlot> fromLine(String line, RawSlot.Stage stage, String sessionId) {
        List<RawSlot> out = new ArrayList<>();
        if (line == null || line.isBlank()) return out;
        String msg = line;

        if (MISSING_SYMBOL.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "MissingSymbol", "compileJava", msg, Map.of("pattern","cannot_find_symbol")));
        }
        if (DUPLICATE_CLASS.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "DuplicateClass", "compileJava", msg, Map.of("pattern","duplicate_class")));
        }
        if (ILLEGAL_START.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "IllegalStartOfType", "compileJava", msg, Map.of("pattern","illegal_start_of_type")));
        }
        if (CLASS_EXPECTED.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "ClassOrInterfaceExpected", "compileJava", msg, Map.of("pattern","class_interface_expected")));
        }
        if (PACKAGE_NOT_FOUND.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "PackageNotFound", "compileJava", msg, Map.of("pattern","package_not_found")));
        }
        if (ILLEGAL_ESCAPE_CHAR.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "IllegalEscapeCharacter", "compileJava", msg, Map.of("pattern","illegal_escape_char")));
        }
        if (BAD_HYPHEN_ESCAPE.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "BadHyphenEscape", "compileJava", msg, Map.of("pattern","bad_hyphen_escape")));
        }
        if (SPLIT_RAW_WS.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "SplitRawWhitespace", "compileJava", msg, Map.of("pattern","split_raw_ws")));
        }
        if (BEAN_NAME_CONFLICT.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "BeanNameConflict", "spring", msg, Map.of("pattern","bean_name_conflict")));
        }
        if (CLASS_NOT_FOUND.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "ClassNotFound", "runtime", msg, Map.of("pattern","class_not_found")));
        }
        if (CNF_BM25_INDEX.matcher(msg).find()) {
            out.add(slot(sessionId, stage, "CannotFindBm25LocalIndex", "compileJava", msg, Map.of("pattern","cnf_bm25_index")));
        }

        return out;
    }

    private static RawSlot slot(String sessionId, RawSlot.Stage stage, String code, String path, String msg, Map<String,String> tags) {
        return RawSlot.builder()
                .sessionId(sessionId)
                .stage(stage != null ? stage : RawSlot.Stage.BUILD)
                .code(code)
                .path(path)
                .message(msg.length() > 2000 ? msg.substring(0, 2000) : msg)
                .tags(tags)
                .ts(Instant.now())
                .build();
    }
}