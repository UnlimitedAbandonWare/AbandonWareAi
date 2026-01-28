package com.example.lms.location;

import com.example.lms.location.route.DirectionsClient;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Formatters#renderEta(com.example.lms.location.route.DirectionsClient.EtaResult)}.
 *
 * <p>This test suite verifies that the {@code renderEta} method correctly handles
 * {@code null} results, formats durations into minutes when no description is
 * provided and preserves descriptions when they are supplied.</p>
 */
class FormattersEtaTest {

    @Test
    void renderEta_null_returnsFallback() {
        String s = Formatters.renderEta(null);
        assertThat(s).contains("경로를 계산할 수 없습니다");
    }

    @Test
    void renderEta_withSeconds_noDescription_usesMinutes() {
        // Approximately 10.08 minutes should round to 10 minutes.
        var eta = new DirectionsClient.EtaResult(605.0, null);
        String s = Formatters.renderEta(eta);
        assertThat(s).contains("약 10분");
    }

    @Test
    void renderEta_withDescription_prefersDescription() {
        var eta = new DirectionsClient.EtaResult(1200.0, "차로 15분 정도 예상돼요.");
        String s = Formatters.renderEta(eta);
        assertThat(s).isEqualTo("차로 15분 정도 예상돼요.");
    }
}