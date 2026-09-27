package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatUiOverflowContractTest {

    private static final Pattern STANDALONE_MESSAGE_RULE = Pattern.compile(
            "(?m)^[\\t ]*\\.message[\\t ]*\\{(?<body>[^{}]*)}");

    @Test
    void canonicalMessageRuleWrapsUnbrokenTokensAndPreservesWhitespace() throws Exception {
        String css = Files.readString(
                Path.of("main/resources/static/css/chat-style.css"),
                StandardCharsets.UTF_8);
        List<Map<String, String>> messageRules = standaloneMessageRules(stripCompleteComments(css));
        List<Map<String, String>> canonicalOwners = messageRules.stream()
                .filter(rule -> "78ch".equals(rule.get("max-width")))
                .filter(rule -> "pre-wrap".equals(rule.get("white-space")))
                .toList();

        assertEquals(1, canonicalOwners.size(),
                "exactly one standalone .message rule must own layout and whitespace");
        Map<String, String> canonicalOwner = canonicalOwners.get(0);
        for (Map<String, String> rule : messageRules) {
            if (rule != canonicalOwner) {
                assertFalse(rule.containsKey("overflow-wrap"),
                        "only the canonical .message rule may declare overflow-wrap");
            }
        }
        assertEquals("anywhere", canonicalOwner.get("overflow-wrap"),
                "canonical .message rule must wrap unbroken tokens");
    }

    private static String stripCompleteComments(String css) {
        StringBuilder uncommented = new StringBuilder(css.length());
        int cursor = 0;
        while (cursor < css.length()) {
            int commentStart = css.indexOf("/*", cursor);
            if (commentStart < 0) {
                uncommented.append(css, cursor, css.length());
                break;
            }
            uncommented.append(css, cursor, commentStart).append(' ');
            int commentEnd = css.indexOf("*/", commentStart + 2);
            assertTrue(commentEnd >= 0, "CSS comments must be terminated");
            cursor = commentEnd + 2;
        }
        return uncommented.toString();
    }

    private static List<Map<String, String>> standaloneMessageRules(String css) {
        Matcher matcher = STANDALONE_MESSAGE_RULE.matcher(css);
        List<Map<String, String>> rules = new ArrayList<>();
        while (matcher.find()) {
            rules.add(effectiveDeclarations(matcher.group("body")));
        }
        return List.copyOf(rules);
    }

    private static Map<String, String> effectiveDeclarations(String body) {
        Map<String, String> declarations = new LinkedHashMap<>();
        for (String declaration : body.split(";")) {
            int separator = declaration.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String property = declaration.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = declaration.substring(separator + 1).trim();
            if (!property.isEmpty() && !value.isEmpty()) {
                declarations.put(property, value);
            }
        }
        return declarations;
    }
}
