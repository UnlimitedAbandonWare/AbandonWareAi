package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptPoseInputSanitizerTest {

    @Test
    void blocksSupabaseKeyShapedPrivatePayload() {
        String secret = "sb_secret_" + "poseinput01";

        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(
                "debug query " + secret,
                new PromptPoseProperties());

        assertTrue(input.blocked());
        assertEquals("private_payload", input.skipReason());
        assertFalse(input.preview().contains(secret));
    }
}
