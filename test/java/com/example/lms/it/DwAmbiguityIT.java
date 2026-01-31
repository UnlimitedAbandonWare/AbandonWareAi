package com.example.lms.it;

import com.example.lms.analysis.DefaultSenseDisambiguator;
import com.example.lms.analysis.SenseDisambiguator;
import org.junit.jupiter.api.Test;
import java.util.List;




import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration style test covering the sense disambiguation logic for the
 * ambiguous Korean query “dw아카데미가 뭐야?”.  The test constructs a set
 * of mock web snippets representing both local academy pages (.kr, Korean
 * language) and the global Deutsche Welle Akademie.  The default
 * disambiguator should rank the local academy ahead of the international
 * broadcaster for a Korean query.
 */
public class DwAmbiguityIT {

    /**
     * A simple mock snippet representation.  The sense disambiguator only
     * inspects {@link Object#toString()} so this class stores relevant
     * information in its {@code toString()} method.
     */
    private static class FakeSnippet {
        final String text;
        FakeSnippet(String text) { this.text = text; }
        @Override public String toString() { return text; }
    }

    @Test
    public void korean_locale_prioritises_local_academy() {
        SenseDisambiguator dis = new DefaultSenseDisambiguator();
        List<FakeSnippet> top = List.of(
                new FakeSnippet("대전 dw아카데미 학원 국비지원 - dwacademy.co.kr"),
                new FakeSnippet("dw아카데미 대전 학원 소개"),
                new FakeSnippet("DW Akademie international media training - dw.com")
        );
        var result = dis.candidates("dw아카데미가 뭐야?", (List<?>) (List<?>) top);
        assertThat(result.senses()).isNotEmpty();
        // First sense should be the local academy
        assertThat(result.senses().get(0).id()).isEqualTo("local-academy");
        assertThat(result.senses().get(0).label()).isEqualTo("대전 학원");
        // The delta should reflect some ambiguity but be above zero
        assertThat(result.delta()).isGreaterThan(0.0);
    }
}