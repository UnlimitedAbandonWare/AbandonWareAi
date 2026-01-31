package com.example.lms.entity;

import org.junit.jupiter.api.Test;



import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple tests ensuring that {@link TranslationMemory} initialises the score
 * field with a non-null default value.  The entity defines {@link jakarta.persistence.Column#nullable()}
 * and a default of 0.0 on the {@code score} field via Lombok's
 * {@code @Builder.Default}.  When constructing the entity via the
 * no-args constructor the score should therefore be non-null and equal to 0.0.
 */
public class TranslationMemoryTest {

    @Test
    public void scoreIsInitialisedInNoArgConstructor() {
        TranslationMemory tm = new TranslationMemory();
        assertThat(tm.getScore()).isNotNull();
        assertThat(tm.getScore()).isEqualTo(0.0);
    }

    @Test
    public void scoreIsInitialisedInBuilder() {
        TranslationMemory tm = TranslationMemory.builder().build();
        assertThat(tm.getScore()).isNotNull();
        assertThat(tm.getScore()).isEqualTo(0.0);
    }
}