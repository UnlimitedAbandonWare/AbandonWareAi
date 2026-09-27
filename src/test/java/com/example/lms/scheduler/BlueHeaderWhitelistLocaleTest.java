package com.example.lms.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlueHeaderWhitelistLocaleTest {

    @Test
    void requestIdHeaderMatchingIsIndependentOfDefaultLocale() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-REQUEST-ID", "request-123");

        Map<String, String> extracted;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            extracted = BlueHeaderWhitelist.extract(headers);
        } finally {
            Locale.setDefault(previous);
        }

        assertEquals(Map.of("x-request-id", "request-123"), extracted);
    }
}
