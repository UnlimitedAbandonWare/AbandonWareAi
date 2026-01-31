// src/main/java/com/example/lms/config/GoogleTranslateProperties.java
package com.example.lms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;




@Component
@ConfigurationProperties(prefix = "google.translate")
@Getter @Setter
public class GoogleTranslateProperties {
    /**
     * Google Translate API keys for rotating when one exceeds quota.
     */
    private List<String> keys;
}