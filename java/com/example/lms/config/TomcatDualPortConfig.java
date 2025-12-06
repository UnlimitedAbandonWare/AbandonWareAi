package com.example.lms.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;



/**
 * Configure embedded Tomcat to:
 *  - serve HTTPS as the primary connector on port 443 (SSL is taken from application properties)
 *  - expose an additional plain HTTP connector on port 80 for legacy URLs (http://host/chat).
 * 
 * This avoids the previous misconfiguration where TLS was bound to port 80.
 * No application.yml/properties changes are required.
 */
@Configuration
@ConditionalOnWebApplication
public class TomcatDualPortConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        // Primary connector → HTTPS on 443 (SSL settings from application properties)
        // Spring Boot will apply keystore/SSL config; we only change the port.
        factory.setPort(443);

        // Additional connector → plain HTTP on 80
        Connector http = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        http.setScheme("http");
        http.setPort(80);
        http.setSecure(false);
        factory.addAdditionalTomcatConnectors(http);
    }
}