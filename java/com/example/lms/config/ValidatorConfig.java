package com.example.lms.config;

import com.example.lms.service.verification.NamedEntityValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



/**
 * Configuration for optional validators.  The {@link NamedEntityValidator}
 * is not annotated as a component by default so that it can be enabled or
 * disabled via application properties.  This configuration class creates
 * the validator bean when {@code validator.named-entity.enabled=true}
 * (the default) and omits it otherwise.  Spring will then inject the
 * validator into services via {@code @Autowired(required=false)}.
 */
@Configuration
public class ValidatorConfig {

    /**
     * Provides a NamedEntityValidator bean only when explicitly enabled.  If
     * disabled via configuration the bean will not be registered and any
     * {@code @Autowired(required = false)} injections will receive {@code null}.
     */
    @Bean
    @ConditionalOnProperty(name = "validator.named-entity.enabled", havingValue = "true", matchIfMissing = true)
    public NamedEntityValidator namedEntityValidator() {
        return new NamedEntityValidator();
    }
}