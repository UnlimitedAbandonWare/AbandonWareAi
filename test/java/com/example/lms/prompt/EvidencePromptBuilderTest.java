package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PromptBuilder} that verify evidence-aware behavior.  When web or RAG
 * evidence is present, the builder should harvest key entities into the MUST_INCLUDE
 * section and adjust the instruction guidance accordingly.  When no evidence is
 * available, the instructions should explicitly tell the assistant to return
 * "정보 없음".  These tests create lightweight proxy implementations of the
 * {@link Content} interface so that only the methods used by {@link PromptBuilder}
 * (namely textSegment().text() and toString()) need to be implemented.  This
 * avoids a hard dependency on the full LangChain4j content classes during test
 * compilation.
 */
public class EvidencePromptBuilderTest {

    /**
     * Constructs a dynamic proxy that implements the {@link Content} interface.
     * The returned object supports the methods invoked by {@link PromptBuilder}:<br>
     * - {@code textSegment()} returns another proxy with a {@code text()} method
     *   yielding the provided snippet<br>
     * - {@code toString()} returns the snippet itself<br>
     * All other methods return {@code null}.  This is sufficient for the tests
     * because the builder only inspects these two methods.
     *
     * @param snippet the text to expose via textSegment().text() and toString()
     * @return a proxy implementing Content that exposes the supplied snippet
     */
    private static Content newContent(String snippet) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                // Provide a proxy for textSegment() with a text() method
                if ("textSegment".equals(name)) {
                    Class<?> returnType = method.getReturnType();
                    return Proxy.newProxyInstance(
                            returnType.getClassLoader(),
                            new Class[]{returnType},
                            (Object p, Method m, Object[] a) -> {
                                if ("text".equals(m.getName())) {
                                    return snippet;
                                }
                                if ("toString".equals(m.getName())) {
                                    return snippet;
                                }
                                return null;
                            }
                    );
                }
                // Return snippet for toString()
                if ("toString".equals(name)) {
                    return snippet;
                }
                // Default: return null for unhandled methods
                return null;
            }
        };
        return (Content) Proxy.newProxyInstance(
                Content.class.getClassLoader(),
                new Class[]{Content.class},
                handler
        );
    }

    /**
     * When evidence is present, MUST_INCLUDE and evidence-aware instructions
     * should be generated.  The MUST_INCLUDE section must contain tokens
     * derived from the evidence snippets, and the instructions should
     * encourage listing candidate pairings instead of answering with
     * "정보 없음".
     */
    @Test
    public void buildWithEvidenceIncludesMustIncludeAndGuidance() {
        // Create Content proxies for web evidence
        Content web1 = newContent("푸리나는 훌륭한 파티 지원 역할을 한다.");
        Content web2 = newContent("에스코피에는 스커크와 높은 시너지를 보인다.");
        // Build a PromptContext with web evidence and no RAG evidence
        PromptContext ctx = PromptContext.builder()
                .userQuery("원신에서 스커크와 조합이 좋은 캐릭터")
                .web(List.of(web1, web2))
                .build();
        PromptBuilder pb = new PromptBuilder();
        // Build the context string and verify MUST_INCLUDE section
        String context = pb.build(ctx);
        assertTrue(context.contains("### MUST_INCLUDE"), "MUST_INCLUDE section should be present when evidence exists");
        assertTrue(context.contains("푸리나"), "MUST_INCLUDE should list tokens extracted from evidence");
        assertTrue(context.contains("에스코피에"), "MUST_INCLUDE should list tokens extracted from evidence");
        // Build instructions and verify evidence-aware guidance
        String instructions = pb.buildInstructions(ctx);
        assertTrue(instructions.contains("When uncertain but evidence exists"),
                "Instructions should prompt listing candidate pairings when evidence exists");
        assertFalse(instructions.contains("If no evidence is available"),
                "Instructions should not mention lack of evidence when evidence is present");
    }

    /**
     * When no evidence is provided, the instructions should explicitly guide
     * the assistant to respond with "정보 없음" when uncertain.  The absence of
     * evidence should suppress the candidate listing guidance.
     */
    @Test
    public void buildInstructionsWithoutEvidenceFallsBack() {
        // Build a PromptContext with no web or RAG evidence
        PromptContext ctx = PromptContext.builder()
                .userQuery("원신에서 스커크와 조합이 좋은 캐릭터")
                .build();
        PromptBuilder pb = new PromptBuilder();
        String instructions = pb.buildInstructions(ctx);
        assertTrue(instructions.contains("If no evidence is available"),
                "Instructions should indicate no evidence when none is provided");
        assertFalse(instructions.contains("When uncertain but evidence exists"),
                "Evidence guidance should not appear when no evidence is provided");
    }
}