package com.example.lms.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class HtmlTextUtilTest {

    @Test
    void htmlTextUtilDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/util/HtmlTextUtil.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}", Pattern.DOTALL)
                .matcher(source)
                .find());
    }

    @Test
    void extractsAndNormalizesHtmlFragmentsWithoutRawTagLeak() {
        String html = "- <a href=\"//example.com/path\">A&nbsp;&amp;&nbsp;B</a>: <b>snippet</b>";

        assertEquals("//example.com/path", HtmlTextUtil.extractFirstHref(html));
        assertEquals("https://example.com/path", HtmlTextUtil.normalizeUrl(HtmlTextUtil.extractFirstHref(html)));
        assertEquals("A & B", HtmlTextUtil.extractAnchorText(html));
        assertEquals("snippet", HtmlTextUtil.stripAndCollapse(HtmlTextUtil.afterAnchor(html)));
    }
}
