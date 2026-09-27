package com.example.lms.conversation.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationNoiseClassifierCodeBoundaryTest {
    static final String SHORT_PROSE = "Please explain how to import a package and why a public class exposes a function to its callers. We are discussing design in ordinary prose.";
    static final String TECHNICAL_PROSE = SHORT_PROSE
            + " The team needs an explanation of the boundary between a reusable interface and the implementation hidden behind it."
            + " Our review should compare two approaches, describe their effect on maintenance, and explain how a newcomer can understand the responsibility of each component."
            + " A useful answer should preserve the original question and show the tradeoffs in complete sentences rather than supplying an implementation dump."
            + " This conversation concerns naming and design choices, so its meaning remains valuable when the message grows beyond a short paragraph.";
    static final String ARTICLE_PROSE = "The deployment review explains the fallback path, the retry budget, the verification record, and the planned handoff.";
    private final ConversationNoiseClassifier classifier = new ConversationNoiseClassifier();

    @Test
    void longTechnicalDiscussionRemainsHuman() {
        assertThat(TECHNICAL_PROSE.length()).isGreaterThanOrEqualTo(400);
        assertHuman(TECHNICAL_PROSE);
    }

    @Test
    void shortTechnicalDiscussionRemainsHuman() {
        assertHuman(SHORT_PROSE);
    }

    @Test
    void separateProseLinesAreNotDeclarations() {
        assertHuman("Import a package only after discussing ownership.\n"
                + "A public class can expose a function to its callers.\n"
                + "Consider the meaning of const and var in an explanation.\n" + TECHNICAL_PROSE);
    }

    @Test
    void InlineCodeMentionsInDiscussionAreNotADump() {
        assertHuman("Explain `import java.util.List;`, `package fixture;`, and `public class Example {}` in context. "
                + TECHNICAL_PROSE);
    }

    @Test
    void javaSourceDumpRemainsQuarantined() {
        assertCode(javaDump());
    }

    @Test
    void minifiedJavaDumpRemainsQuarantined() {
        assertCode(javaDump().replace("\n", " "));
    }

    @Test
    void javascriptSourceDumpRemainsQuarantined() {
        assertCode(javascriptDump());
    }

    @Test
    void minifiedJavascriptDumpRemainsQuarantined() {
        assertCode(javascriptDump().replace("\n", " "));
    }

    @Test
    void anonymousFunctionAndDestructuredBindingRemainCodeSignals() {
        assertCode("import { helper } from './helper.js';\nconst { result } = helper();\n"
                + "var render = function(value) { return value; };\n"
                + "render(result);\n".repeat(30));
    }

    @Test
    void longQualifiedPackageDoesNotExhaustRegexStack() {
        assertCode("package " + "fixture.".repeat(5000) + "example;\n"
                + "import java.util.List;\npublic class Example {}\n");
    }

    @Test
    void longQualifiedImportDoesNotExhaustRegexStack() {
        assertCode("package fixture;\nimport " + "fixture.".repeat(5000) + "Example;\n"
                + "public class Example {}\n");
    }

    @Test
    void shortSnippetKeepsExistingLengthThreshold() {
        assertHuman("package fixture; import java.util.List; public class Example {} ");
    }

    @Test
    void recurringArticleInOrdinarySentenceRemainsHuman() {
        assertThat(ARTICLE_PROSE.length()).isLessThan(400);
        assertHuman(ARTICLE_PROSE);
    }

    @Test
    void repeatedTokenNoiseRemainsQuarantined() {
        var decision = classifier.classifyText("hello HELLO\nhello\thello hello");
        assertThat(decision.kind()).isEqualTo(ConversationMessageKind.QUARANTINED);
        assertThat(decision.reason()).isEqualTo("repeated_noise");
        assertThat(decision.ingestible()).isFalse();
    }

    @Test
    void lowDiversityNoiseRemainsQuarantined() {
        assertThat(classifier.classifyText("aaa aaa aaa aaa aaa").reason()).isEqualTo("repeated_noise");
    }

    private void assertHuman(String text) {
        var decision = classifier.classifyText(text);
        assertThat(decision.kind()).isEqualTo(ConversationMessageKind.HUMAN_MESSAGE);
        assertThat(decision.ingestible()).isTrue();
    }

    private void assertCode(String text) {
        assertThat(text.length()).isGreaterThanOrEqualTo(400);
        var decision = classifier.classifyText(text);
        assertThat(decision.kind()).isEqualTo(ConversationMessageKind.QUARANTINED);
        assertThat(decision.reason()).isEqualTo("code_or_internal_dump");
        assertThat(decision.ingestible()).isFalse();
    }

    private static String javaDump() {
        StringBuilder text = new StringBuilder("package fixture;\nimport java.util.List;\npublic class Example {\n");
        for (int i = 0; i < 12; i++) {
            text.append("  public String step").append(i).append("() { return String.valueOf(").append(i).append("); }\n");
        }
        return text.append("}\n").toString();
    }

    private static String javascriptDump() {
        return "import { helper } from './helper.js';\nconst label = 'fixture';\n"
                + "function render(value) { return helper(value); }\n"
                + "render(label);\n".repeat(30);
    }
}
