package com.example.lms.guard.rulebreak;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuleBreakEvaluatorTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenCases")
    void headerAndProbeValidationPreserveExactTrimmedTokenSemantics(
            String label, String configured, String supplied, boolean expectedActive) {
        RuleBreakEvaluator evaluator = new RuleBreakEvaluator();
        ReflectionTestUtils.setField(evaluator, "adminToken", configured);
        ReflectionTestUtils.setField(evaluator, "defaultTtl", 60);
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (supplied != null) request.addHeader("X-RuleBreak-Token", supplied);

        RuleBreakContext header = evaluator.evaluateFromHeaders(request);
        RuleBreakContext probe = evaluator.evaluateFromProbe(true, null, 60, supplied);

        assertEquals(expectedActive, header.isActive());
        assertEquals(expectedActive, probe.isActive());
        assertFalse(evaluator.evaluateFromProbe(false, null, 60, supplied).isActive());
        if (expectedActive) {
            assertEquals(RuleBreakPolicy.SAFE_EXPLORE, header.getPolicy());
            assertEquals(header.getTokenHash(), probe.getTokenHash());
            assertEquals(64, header.getTokenHash().length());
            assertFalse(header.toString().contains("synthetic-rb"));
        }
        assertFalse(TraceStore.getAll().toString().contains("synthetic-rb"));
    }

    private static Stream<Arguments> tokenCases() {
        String token = "synthetic-rb-Ab3z";
        String malformed = "synthetic-rb-" + (char) 0xd800;
        return Stream.of(
                Arguments.of("exact", token, token, true),
                Arguments.of("first-mismatch", token, "S" + token.substring(1), false),
                Arguments.of("middle-mismatch", token, "synthetic-rb-Ac3z", false),
                Arguments.of("last-mismatch", token, "synthetic-rb-Ab3y", false),
                Arguments.of("length-mismatch", token, token + "x", false),
                Arguments.of("trimmed-exact", "  " + token, token + "  ", true),
                Arguments.of("supplementary-exact", "synthetic-rb-" + Character.toString(0x1f600),
                        "synthetic-rb-" + Character.toString(0x1f600), true),
                Arguments.of("supplementary-mismatch", "synthetic-rb-" + Character.toString(0x1f600),
                        "synthetic-rb-" + Character.toString(0x1f601), false),
                Arguments.of("malformed-versus-replacement", malformed, "synthetic-rb-?", false),
                Arguments.of("different-malformed-unit", malformed, "synthetic-rb-" + (char) 0xd801, false),
                Arguments.of("same-malformed-unit", malformed, malformed, true),
                Arguments.of("missing-request", token, null, false),
                Arguments.of("blank-request", token, "  ", false),
                Arguments.of("placeholder-request", token, "changeme", false),
                Arguments.of("placeholder-config", "changeme", "changeme", false));
    }

    @Test
    void malformedUtf16UnitsWouldAliasUnderUtf8Encoding() {
        String first = "synthetic-rb-" + (char) 0xd800;
        String second = "synthetic-rb-" + (char) 0xd801;
        assertFalse(first.equals(second));
        assertArrayEquals(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void compiledTokenValidatorInvokesConstantTimePrimitiveWithoutStringEquality() throws Exception {
        AtomicInteger constantTimeCalls = new AtomicInteger();
        AtomicInteger ordinaryEqualityCalls = new AtomicInteger();
        new ClassReader(RuleBreakEvaluator.class.getName()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!name.equals("validate") || !descriptor.startsWith(
                        "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals("java/security/MessageDigest")
                                && method.equals("isEqual") && desc.equals("([B[B)Z")) constantTimeCalls.incrementAndGet();
                        if (owner.equals("java/lang/String") && method.equals("equals")) ordinaryEqualityCalls.incrementAndGet();
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertEquals(1, constantTimeCalls.get(), "core validator must invoke the constant-time primitive");
        assertEquals(0, ordinaryEqualityCalls.get(), "core validator must not compare tokens through String.equals");
    }

    @Test
    void malformedTtlFallsBackAndLeavesRedactedBreadcrumb() throws Exception {
        Method parseInt = RuleBreakEvaluator.class.getDeclaredMethod("parseInt", String.class, int.class);
        parseInt.setAccessible(true);

        Object parsed = parseInt.invoke(null, "not-a-number private ttl should not leak", 60);

        assertEquals(60, parsed);
        assertEquals("ruleBreak.ttl", TraceStore.get("rulebreak.suppressed.stage"));
        assertEquals("NumberFormatException", TraceStore.get("rulebreak.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("rulebreak.suppressed.ruleBreak.ttl"));
        assertEquals("NumberFormatException",
                TraceStore.get("rulebreak.suppressed.ruleBreak.ttl.errorType"));
        assertFalse(String.valueOf(TraceStore.getByPrefix("rulebreak."))
                .contains("private ttl should not leak"));
    }
}
