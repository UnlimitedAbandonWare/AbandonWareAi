package com.example.lms.guard;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuardProfileLocaleTest {

    @Test
    void configuredProfileParsingIsIndependentOfDefaultLocale() {
        GuardProfileProps props = new GuardProfileProps();
        props.setProfile("profile_free");

        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertEquals(GuardProfile.PROFILE_FREE, props.currentProfile());
        } finally {
            Locale.setDefault(previous);
        }
    }
}
