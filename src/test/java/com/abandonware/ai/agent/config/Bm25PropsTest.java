package com.abandonware.ai.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25PropsTest {

    @Test
    void gettersClampInvalidNumericSettings() {
        Bm25Props props = new Bm25Props();
        props.setTopK(-5);
        props.setMinSnippetChars(-20);
        props.setMaxDocs(0);

        assertThat(props.getTopK()).isEqualTo(1);
        assertThat(props.getMinSnippetChars()).isZero();
        assertThat(props.getMaxDocs()).isEqualTo(1);
    }
}
