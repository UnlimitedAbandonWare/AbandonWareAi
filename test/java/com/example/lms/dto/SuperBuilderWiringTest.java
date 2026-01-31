package com.example.lms.dto;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying that Lombok's {@code @SuperBuilder} is correctly
 * configured on both the base and extended chat request DTOs.  These
 * tests ensure that the builder API remains compatible with
 * existing code after migrating from {@code @Builder} to
 * {@code @SuperBuilder}.
 */
public class SuperBuilderWiringTest {

    @Test
    public void chatRequestDtoBuilderShouldWork() {
        ChatRequestDto dto = ChatRequestDto.builder()
                .message("hello")
                .build();
        assertNotNull(dto);
        assertEquals("hello", dto.getMessage());
    }

    @Test
    public void extendedChatRequestDtoBuilderShouldWork() {
        ExtendedChatRequestDto ext = ExtendedChatRequestDto.builder()
                .message("world")
                .fileSearch(true)
                .build();
        assertNotNull(ext);
        assertEquals("world", ext.getMessage());
        assertTrue(ext.getFileSearch());
    }
}