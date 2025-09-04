package com.example.lms.config;

import com.example.lms.plugin.image.GeminiImageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Provides a primary {@link GeminiImageProperties} bean when multiple
 * {@code GeminiImageProperties} candidates are present.  In some builds the
 * properties class may be registered both via component scanning (due to
 * {@code @Component}) and via configuration properties binding.  Without a
 * primary bean the Spring container will report an ambiguous dependency when
 * injecting by type.  This configuration resolves that ambiguity by
 * registering a new {@code GeminiImageProperties} bean marked as
 * {@link Primary}.  The instance returned here delegates to the first
 * available properties bean in the context.
 */
@Configuration
public class GeminiImagePrimaryConfiguration {

    /**
     * Return a {@code GeminiImageProperties} bean to serve as the primary
     * candidate.  When multiple {@code GeminiImageProperties} beans exist
     * (for example, one from component scanning and one from
     * {@code @ConfigurationProperties}), Spring will inject this primary bean
     * wherever a single {@code GeminiImageProperties} is required.  The
     * underlying instance is chosen deterministically from the list of
     * available candidates.
     *
     * @param candidates the list of all GeminiImageProperties beans
     * @return a primary GeminiImageProperties bean
     */
    @Bean
    @Primary
    public GeminiImageProperties geminiImagePropertiesPrimary(List<GeminiImageProperties> candidates) {
        // Simply return the first candidate in the list.  The ordering is
        // implementation-dependent but deterministic within the same context.
        return candidates.get(0);
    }
}