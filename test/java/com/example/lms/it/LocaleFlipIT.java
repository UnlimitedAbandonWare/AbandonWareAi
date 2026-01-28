package com.example.lms.it;

import com.example.lms.service.rag.fusion.WeightedRrfFuser;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Locale;




import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the locale and language weighting used by {@link WeightedRrfFuser}.  A
 * German locale should favour results from a .de or .com domain over a
 * Korean domain, whereas a Korean locale should favour the .kr domain.
 */
public class LocaleFlipIT {

    @Test
    public void german_locale_prefers_global_dw() {
        WeightedRrfFuser.Result kr = new WeightedRrfFuser.Result("dwacademy.co.kr", "대전 DW아카데미", "한국어 학원", Instant.parse("2020-01-01T00:00:00Z"));
        WeightedRrfFuser.Result de = new WeightedRrfFuser.Result("dw.com", "DW Akademie", "International media training", Instant.parse("2020-01-01T00:00:00Z"));
        double wKr = WeightedRrfFuser.weight(Locale.GERMANY, kr);
        double wDe = WeightedRrfFuser.weight(Locale.GERMANY, de);
        assertThat(wDe).isGreaterThanOrEqualTo(wKr);
    }

    @Test
    public void korean_locale_prefers_local_academy() {
        WeightedRrfFuser.Result kr = new WeightedRrfFuser.Result("dwacademy.co.kr", "대전 DW아카데미", "한국어 학원", Instant.now());
        WeightedRrfFuser.Result de = new WeightedRrfFuser.Result("dw.com", "DW Akademie", "International media training", Instant.now());
        double wKr = WeightedRrfFuser.weight(Locale.KOREA, kr);
        double wDe = WeightedRrfFuser.weight(Locale.KOREA, de);
        assertThat(wKr).isGreaterThan(wDe);
    }
}