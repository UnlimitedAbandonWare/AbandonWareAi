package com.example.lms.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;



/**
 * Spring configuration that activates binding of the
 * {@link NaverFilterProperties} class.  Placing this in its own
 * configuration class avoids cluttering the main application class and
 * ensures that the properties are available to any component via
 * constructor injection.
 */
@Configuration
@EnableConfigurationProperties({NaverFilterProperties.class})
public class NaverFilterConfig {
    // intentionally left blank
}