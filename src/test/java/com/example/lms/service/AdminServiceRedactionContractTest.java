package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AdminServiceRedactionContractTest {

    @Test
    void passwordChangeLogDoesNotWriteRawUsername() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/AdminService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("비밀번호가 변경되었습니다.\", username"));
        assertFalse(source.contains("\"Current principal is not an Administrator: \" + principal"));
        assertFalse(source.contains("username + \" 계정을 찾을 수 없습니다.\""));
        assertTrue(source.contains("principalHash="));
        assertTrue(source.contains("principalLength="));
        assertTrue(source.contains("usernameHash="));
        assertTrue(source.contains("usernameLength="));
        assertTrue(source.contains("usernameHash"));
        assertTrue(source.contains("SafeRedactor.hash12(username)"));
    }
}
