package com.abandonware.ai.example.lms.cfvm;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;



/** Very small regex-based extractor for common Java build errors. */
public class BuildLogSlotExtractor implements RawSlotExtractor {
    private static final Pattern MISSING_SYMBOL = Pattern.compile("(?i)cannot\\s+find\\s+symbol");
    private static final Pattern DUPLICATE_CLASS = Pattern.compile("(?i)^duplicate\\s+class\\s+.+");
    private static final Pattern ILLEGAL_START = Pattern.compile("(?i)illegal\\s+start\\s+of\\s+type");
    private static final Pattern CLASS_EXPECTED = Pattern.compile("(?i)class,\\s*interface,\\s*enum,?\\s*or\\s*record\\s+expected");
    private static final Pattern PACKAGE_NOT_FOUND = Pattern.compile("(?i)package\\s+.+\\s+does\\s+not\\s+exist");
    
    private static final Pattern BEAN_NAME_CONFLICT = Pattern.compile(
            "ConflictingBeanDefinitionException:.*bean name '([^\']+)'",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
private static final Pattern OVERRIDE_MISMATCH = Pattern.compile("(?i)method\\s+does\\s+not\\s+override\\s+or\\s+implement\\s+a\\s+method\\s+from\\s+a\\s+supertype");

    @Override
    public List<RawSlot> extract(Throwable ex, RawSlot.Stage stage, String sessionId) {
        String msg = String.valueOf(ex.getMessage());
        if (MISSING_SYMBOL.matcher(msg).find()) {
            return List.of(RawSlot.builder()
                    .sessionId(sessionId)
                    .stage(stage)
                    .code("MissingSymbol")
                    .path("compileJava")
                    .message(msg)
                    .tags(Map.of("pattern","cannot_find_symbol"))
                    .ts(Instant.now())
                    .build());
        }
        if (DUPLICATE_CLASS.matcher(msg).find()) {
            return List.of(RawSlot.builder()
                    .sessionId(sessionId)
                    .stage(stage)
                    .code("DuplicateClass")
                    .path("compileJava")
                    .message(msg)
                    .tags(Map.of("pattern","duplicate_class"))
                    .ts(Instant.now())
                    .build());
        }
        if (ILLEGAL_START.matcher(msg).find()) {
            return List.of(RawSlot.builder()
                    .sessionId(sessionId)
                    .stage(stage)
                    .code("IllegalStartOfType")
                    .path("compileJava")
                    .message(msg)
                    .tags(Map.of("pattern","illegal_start_of_type"))
                    .ts(Instant.now())
                    .build());
        }
        if (CLASS_EXPECTED.matcher(msg).find()) {
            return List.of(RawSlot.builder()
                    .sessionId(sessionId)
                    .stage(stage)
                    .code("ClassOrInterfaceExpected")
                    .path("compileJava")
                    .message(msg)
                    .tags(Map.of("pattern","class_interface_enum_expected"))
                    .ts(Instant.now())
                    .build());
        }
        if (PACKAGE_NOT_FOUND.matcher(msg).find()) {
            return List.of(RawSlot.builder()
                    .sessionId(sessionId)
                    .stage(stage)
                    .code("PackageNotFound")
                    .path("compileJava")
                    .message(msg)
                    .tags(Map.of("pattern","package_does_not_exist"))
                    .ts(Instant.now())
                    .build());
        }

        if (OVERRIDE_MISMATCH.matcher(msg).find()) {
            return List.of(RawSlot.builder()
                    .sessionId(sessionId)
                    .stage(stage)
                    .code("OverrideMismatch")
                    .path("compileJava")
                    .message(msg)
                    .tags(Map.of("pattern","override_mismatch"))
                    .ts(Instant.now())
                    .build());
        }

                // Bean name conflict (Spring context)
        if (BEAN_NAME_CONFLICT.matcher(msg).find()) {
            var m = BEAN_NAME_CONFLICT.matcher(msg);
            String bean = m.find() ? m.group(1) : "unknown";
            return List.of(RawSlot.builder()
                    .sessionId(sessionId)
                    .stage(stage)
                    .code("BeanNameConflict")
                    .path("bootRun")
                    .message(msg)
                    .tags(Map.of("pattern","bean_name_conflict", "bean", bean))
                    .ts(Instant.now())
                    .build());
        }
        return List.of();
    }
}